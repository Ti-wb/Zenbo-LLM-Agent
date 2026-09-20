import { TURN_STATES } from '../stores/runtime';

export const InteractionAction = Object.freeze({
  WAKE: 'WAKE',
  START_LISTENING: 'START_LISTENING',
  STOP_LISTENING: 'STOP_LISTENING',
  CANCEL_AND_LISTEN: 'CANCEL_AND_LISTEN',
});

export function headPressAction({ sleeping, turnState }) {
  if (sleeping) return InteractionAction.WAKE;
  if (turnState === TURN_STATES.LISTENING) return InteractionAction.STOP_LISTENING;
  if (turnState === TURN_STATES.IDLE) return InteractionAction.START_LISTENING;
  return InteractionAction.CANCEL_AND_LISTEN;
}
