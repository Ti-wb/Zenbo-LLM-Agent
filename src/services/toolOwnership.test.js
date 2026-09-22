import { describe, expect, it } from 'vitest';
import { NATIVE_TOOL_NAMES, ToolOwner, WEB_TOOL_NAMES, toolOwner } from './toolOwnership';

describe('fixed device tool ownership', () => {
  it('keeps physical/status and camera tools native-owned', () => {
    expect(NATIVE_TOOL_NAMES).toEqual([
      'get_system_status',
      'start_robot_following',
      'stop_robot_following',
      'look_at_user',
      'move_robot',
      'capture_camera',
    ]);
    expect(NATIVE_TOOL_NAMES.map(toolOwner)).toEqual(Array(6).fill(ToolOwner.NATIVE));
  });

  it('executes only the two face-state tools in Web', () => {
    expect(WEB_TOOL_NAMES).toEqual(['show_emotion', 'go_to_sleep']);
    expect(WEB_TOOL_NAMES.map(toolOwner)).toEqual(Array(2).fill(ToolOwner.WEB));
    expect(toolOwner('provider_search')).toBe(ToolOwner.UNKNOWN);
  });
});
