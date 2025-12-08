<template>
  <tool
    name="go_to_sleep"
    description="Temporarily stop listening to the user until the robot detects new voice activity."
    @call="handleGoToSleep"
    return
  />
</template>

<script setup>
import { onMounted, onUnmounted } from 'vue';
import { useSleepMode } from '@/composables/useSleepMode';
import { useRobotEvents } from '@/composables/useRobotEvents';

const { sleeping, goToSleep, wakeUp } = useSleepMode();
const { onEventType } = useRobotEvents({ autoConnect: true });

let removeOnVoiceDetect;
let removeOnScreenOff;
let removeOnScreenOn;

function sendReturn(event, payload) {
  event.target.dispatchEvent(new CustomEvent('return', { detail: payload }));
}

function handleGoToSleep(event) {
  if (sleeping.value) {
    sendReturn(event, { ok: true, already_sleeping: true });
    return;
  }

  goToSleep();
  sendReturn(event, { ok: true, sleeping: true });
}

function isHeadButtonPress(event) {
  return event.data && event.data.event_vad_status && event.data.event_vad_status.vad_status === 'BeginCSR_TriggerWord' && event.data.event_vad_status.sound_level === 0;
}

onMounted(() => {
  removeOnVoiceDetect = onEventType('onVoiceDetect', (event) => {
    if (!isHeadButtonPress(event)) {
      return;
    }

    console.log('Head button press detected, toggling sleep mode.');

    if (sleeping.value) {
      wakeUp();
    } else {
      goToSleep();
    }
  });

  // When the device screen turns off (e.g. power button pressed),
  // automatically put the agent into sleep mode so it stops listening.
  removeOnScreenOff = onEventType('ScreenOff', () => {
    if (!sleeping.value) {
      goToSleep();
    }
  });

  // When the screen turns back on, wake the agent up again
  // so it can resume listening if it was previously active.
  removeOnScreenOn = onEventType('ScreenOn', () => {
    if (sleeping.value) {
      wakeUp();
    }
  });
});

onUnmounted(() => {
  if (typeof removeOnVoiceDetect === 'function') {
    removeOnVoiceDetect();
  }

  if (typeof removeOnScreenOff === 'function') {
    removeOnScreenOff();
  }

  if (typeof removeOnScreenOn === 'function') {
    removeOnScreenOn();
  }
});
</script>
