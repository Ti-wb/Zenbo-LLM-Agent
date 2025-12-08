import { ref } from 'vue';
import { useRobotAPI } from './useRobotAPI';
import { user } from '@openai/agents';
import { useTTS } from './useTTS';

// Singleton sleep state shared across the app.
const sleeping = ref(false);

export function useSleepMode() {
  const goToSleep = () => {
    sleeping.value = true;
    useRobotAPI().lockExpression('TIRED');
  };

  const wakeUp = () => {
    sleeping.value = false;
    useRobotAPI().unlockExpression();
    useRobotAPI().setExpression('DEFAULT');
  };

  return {
    sleeping,
    goToSleep,
    wakeUp,
  };
}

