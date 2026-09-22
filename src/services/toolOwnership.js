export const ToolOwner = Object.freeze({
  NATIVE: 'native',
  WEB: 'web',
  UNKNOWN: 'unknown',
});

export const NATIVE_TOOL_NAMES = Object.freeze([
  'get_system_status',
  'start_robot_following',
  'stop_robot_following',
  'look_at_user',
  'move_robot',
  'capture_camera',
]);

export const WEB_TOOL_NAMES = Object.freeze(['show_emotion', 'go_to_sleep']);

const nativeTools = new Set(NATIVE_TOOL_NAMES);
const webTools = new Set(WEB_TOOL_NAMES);

export function toolOwner(toolName) {
  if (nativeTools.has(toolName)) return ToolOwner.NATIVE;
  if (webTools.has(toolName)) return ToolOwner.WEB;
  return ToolOwner.UNKNOWN;
}
