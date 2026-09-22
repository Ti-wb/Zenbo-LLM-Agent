import { describe, expect, it } from 'vitest';
import { TURN_STATES } from '../stores/runtime';
import { headPressAction, InteractionAction } from './interactionPolicy';

describe('headPressAction', () => {
  it('wakes before considering turn state, and toggles awake listening', () => {
    expect(headPressAction({ sleeping: true, turnState: TURN_STATES.SPEAKING })).toBe(InteractionAction.WAKE);
    expect(headPressAction({ sleeping: false, turnState: TURN_STATES.IDLE })).toBe(InteractionAction.START_LISTENING);
    expect(headPressAction({ sleeping: false, turnState: TURN_STATES.LISTENING })).toBe(InteractionAction.STOP_LISTENING);
  });

  it('cancels any busy or failed turn before returning to listening', () => {
    const busyStates = [TURN_STATES.UPLOADING, TURN_STATES.TRANSCRIBING, TURN_STATES.THINKING,
      TURN_STATES.AWAITING_TOOL, TURN_STATES.SYNTHESIZING, TURN_STATES.SPEAKING, TURN_STATES.ERROR];
    expect(busyStates.map((turnState) => [turnState, headPressAction({ sleeping: false, turnState })]))
      .toEqual(busyStates.map((turnState) => [turnState, InteractionAction.CANCEL_AND_LISTEN]));
  });
});
