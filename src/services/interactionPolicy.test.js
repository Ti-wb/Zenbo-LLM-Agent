import { describe, expect, it } from 'vitest';
import { TURN_STATES } from '../stores/runtime';
import { headPressAction, InteractionAction } from './interactionPolicy';

describe('headPressAction', () => {
  it('wakes a sleeping renderer', () => {
    expect(headPressAction({ sleeping: true, turnState: TURN_STATES.IDLE })).toBe(
      InteractionAction.WAKE,
    );
  });

  it('stops listening on a second press while keeping the renderer awake', () => {
    expect(headPressAction({ sleeping: false, turnState: TURN_STATES.LISTENING })).toBe(
      InteractionAction.STOP_LISTENING,
    );
  });

  it.each([
    TURN_STATES.UPLOADING,
    TURN_STATES.TRANSCRIBING,
    TURN_STATES.THINKING,
    TURN_STATES.AWAITING_TOOL,
    TURN_STATES.SYNTHESIZING,
    TURN_STATES.SPEAKING,
    TURN_STATES.ERROR,
  ])('cancels %s and returns to listening', (turnState) => {
    expect(headPressAction({ sleeping: false, turnState })).toBe(
      InteractionAction.CANCEL_AND_LISTEN,
    );
  });
});
