export function createRuntimeTimers(options = {}) {
  const setTimeoutImpl = options.setTimeoutImpl || globalThis.setTimeout?.bind(globalThis);
  const clearTimeoutImpl = options.clearTimeoutImpl || globalThis.clearTimeout?.bind(globalThis);
  let resumeTimer = null;
  let inactivityTimer = null;

  function replace(current, callback, delay) {
    if (current !== null) clearTimeoutImpl?.(current);
    return setTimeoutImpl?.(callback, delay) ?? null;
  }

  return {
    scheduleResume(callback) {
      resumeTimer = replace(resumeTimer, () => {
        resumeTimer = null;
        callback();
      }, 500);
    },
    scheduleInactivity(callback) {
      inactivityTimer = replace(inactivityTimer, () => {
        inactivityTimer = null;
        callback();
      }, 20000);
    },
    clearResume() {
      if (resumeTimer !== null) clearTimeoutImpl?.(resumeTimer);
      resumeTimer = null;
    },
    clearInactivity() {
      if (inactivityTimer !== null) clearTimeoutImpl?.(inactivityTimer);
      inactivityTimer = null;
    },
    clearAll() {
      this.clearResume();
      this.clearInactivity();
    },
  };
}
