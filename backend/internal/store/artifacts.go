package store

import (
	"context"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/jackc/pgx/v5"
)

func (store *Postgres) CreateArtifact(ctx context.Context, artifact application.Artifact) (application.Artifact, error) {
	return inTransaction(ctx, store.pool, pgx.TxOptions{}, func(tx pgx.Tx) (application.Artifact, error) {
		if err := lockKey(ctx, tx, "session:"+artifact.SessionID); err != nil {
			return application.Artifact{}, err
		}
		var allowed bool
		if err := tx.QueryRow(ctx, `
			SELECT EXISTS (
				SELECT 1
				FROM turns JOIN sessions ON sessions.id = turns.session_id
				WHERE turns.id = $1 AND turns.session_id = $2
				  AND turns.state IN ('accepted', 'processing', 'waiting_for_tool')
				  AND sessions.state IN ('active', 'closing')
			)
		`, artifact.TurnID, artifact.SessionID).Scan(&allowed); err != nil {
			return application.Artifact{}, err
		}
		if !allowed {
			return application.Artifact{}, application.ErrTurnTerminal
		}
		return insertArtifactTx(ctx, tx, artifact)
	})
}

func insertArtifactTx(ctx context.Context, tx pgx.Tx, artifact application.Artifact) (application.Artifact, error) {
	value, err := scanArtifact(tx.QueryRow(ctx, `
		INSERT INTO artifacts (
			id, session_id, turn_id, kind, blob_key, mime_type, byte_length,
			sha256, created_at, expires_at
		) VALUES (
			COALESCE(NULLIF($1, '')::uuid, gen_random_uuid()), $2, $3, $4, $5,
			$6, $7, $8, $9, $10
		)
		RETURNING `+artifactColumns,
		artifact.ID, artifact.SessionID, artifact.TurnID, artifact.Kind,
		artifact.BlobKey, artifact.MIMEType, artifact.ByteLength, artifact.SHA256,
		artifact.CreatedAt, artifact.ExpiresAt,
	))
	if err != nil {
		return application.Artifact{}, mapDatabaseError(err)
	}
	return value, nil
}

func (store *Postgres) GetArtifact(ctx context.Context, deviceID, sessionID, artifactID string, now time.Time) (application.Artifact, error) {
	value, err := scanArtifact(store.pool.QueryRow(ctx, `
		SELECT `+artifactColumns+`
		FROM artifacts
		WHERE id = $1 AND session_id = $2
		  AND EXISTS (
		    SELECT 1 FROM sessions
		    WHERE sessions.id = artifacts.session_id AND sessions.device_id = $3
		  )
	`, artifactID, sessionID, deviceID))
	if err != nil {
		return application.Artifact{}, mapDatabaseError(err)
	}
	if value.DeletionState != "live" || !value.ExpiresAt.After(now) {
		return application.Artifact{}, application.ErrArtifactGone
	}
	return value, nil
}

func (store *Postgres) LeaseArtifactForDeletion(
	ctx context.Context,
	artifactID, owner string,
	now time.Time,
	lease time.Duration,
) (application.ArtifactLease, error) {
	value, err := scanArtifact(store.pool.QueryRow(ctx, `
		UPDATE artifacts
		SET deletion_state = 'deleting', deletion_lease_owner = $2,
		    deletion_lease_until = $3
		WHERE id = $1 AND deletion_state = 'live'
		RETURNING `+artifactColumns,
		artifactID, owner, now.Add(lease),
	))
	if err != nil {
		return application.ArtifactLease{}, mapDatabaseError(err)
	}
	return application.ArtifactLease{Artifact: value}, nil
}

func (store *Postgres) LeaseExpiredArtifacts(ctx context.Context, owner string, now time.Time, lease time.Duration, limit int) ([]application.ArtifactLease, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := store.pool.Query(ctx, `
		WITH candidates AS (
			SELECT id FROM artifacts
			WHERE (
				(deletion_state = 'live' AND expires_at <= $1)
				OR
				(deletion_state = 'deleting' AND deletion_lease_until <= $1)
			)
			ORDER BY expires_at
			FOR UPDATE SKIP LOCKED
			LIMIT $2
		)
		UPDATE artifacts AS artifacts
		SET deletion_state = 'deleting', deletion_lease_owner = $3,
		    deletion_lease_until = $4
		FROM candidates
		WHERE artifacts.id = candidates.id
		RETURNING artifacts.id::text, artifacts.session_id::text,
		          artifacts.turn_id::text, artifacts.kind, artifacts.blob_key,
		          artifacts.mime_type, artifacts.byte_length, artifacts.sha256,
		          artifacts.created_at, artifacts.expires_at,
		          artifacts.deletion_state,
		          COALESCE(artifacts.deletion_lease_owner, ''),
		          artifacts.deletion_lease_until, artifacts.deleted_at
	`, now, limit, owner, now.Add(lease),
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var result []application.ArtifactLease
	for rows.Next() {
		artifact, err := scanArtifact(rows)
		if err != nil {
			return nil, err
		}
		result = append(result, application.ArtifactLease{Artifact: artifact})
	}
	return result, rows.Err()
}

func (store *Postgres) MarkArtifactDeleted(ctx context.Context, artifactID, owner string, now time.Time) error {
	tag, err := store.pool.Exec(ctx, `
		UPDATE artifacts
		SET deletion_state = 'deleted', deletion_lease_owner = NULL,
		    deletion_lease_until = NULL, deleted_at = $3
		WHERE id = $1 AND deletion_state = 'deleting'
		  AND deletion_lease_owner = $2
	`, artifactID, owner, now)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return application.ErrLeaseLost
	}
	return nil
}

func (store *Postgres) ReleaseArtifactLease(ctx context.Context, artifactID, owner string) error {
	tag, err := store.pool.Exec(ctx, `
		UPDATE artifacts
		SET deletion_state = 'live', deletion_lease_owner = NULL,
		    deletion_lease_until = NULL
		WHERE id = $1 AND deletion_state = 'deleting'
		  AND deletion_lease_owner = $2
	`, artifactID, owner)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return application.ErrLeaseLost
	}
	return nil
}

func (store *Postgres) BlobKeyExists(ctx context.Context, blobKey string) (bool, error) {
	var exists bool
	if err := store.pool.QueryRow(ctx, `
		SELECT EXISTS (
			SELECT 1 FROM artifacts WHERE blob_key = $1
		)
	`, blobKey).Scan(&exists); err != nil {
		return false, err
	}
	return exists, nil
}

func enqueueInputArtifactDeletionJobsTx(
	ctx context.Context,
	tx pgx.Tx,
	sessionID, turnID string,
	now time.Time,
) error {
	_, err := tx.Exec(ctx, `
		INSERT INTO jobs (
			job_type, session_id, turn_id, dedupe_key, payload,
			status, available_at, created_at, updated_at
		)
		SELECT
			'delete_artifact',
			artifacts.session_id,
			artifacts.turn_id,
			'artifact:' || artifacts.id::text,
			jsonb_build_object('artifactId', artifacts.id::text),
			'queued', $3, $3, $3
		FROM artifacts
		WHERE artifacts.session_id = $1
		  AND ($2 = '' OR artifacts.turn_id = NULLIF($2, '')::uuid)
		  AND artifacts.kind = 'input_audio'
		  AND artifacts.deletion_state = 'live'
		  AND artifacts.expires_at <= $3
		ON CONFLICT (job_type, dedupe_key)
			WHERE dedupe_key IS NOT NULL
			  AND status IN ('queued', 'running')
		DO NOTHING
	`, sessionID, turnID, now)
	return err
}
