export function deviceMessage(error) {
  const messages = {
    CAMERA_BUSY: '相機正由其他功能使用，請先停止跟隨後再試。',
    CAMERA_RELEASE_FAILED: '相機尚未完成關閉，跟隨暫停；請重試關閉相機。',
    CAMERA_DISABLED: '請先開啟相機。',
    CAMERA_PERMISSION_REQUIRED: '請在 Zenbo 允許相機權限，再開啟相機。',
    CAMERA_PERMISSION_DENIED: '請在 Zenbo 允許相機權限，再開啟相機。',
    MOTION_POWER_CONNECTED: 'Zenbo 接著充電線，請拔除後再啟動移動或跟隨。',
    MOTION_USB_CONNECTED: 'Zenbo 接著 USB，請拔除後再啟動移動或跟隨。',
    FOLLOW_START_TIMEOUT: '跟隨功能尚未完成準備，請稍後再試。',
    FOLLOW_TARGET_NOT_FOUND: '目前找不到可跟隨的人，請站在 Zenbo 正前方再試。',
    MOTION_DISABLED: '請先在 Zenbo 主畫面開啟「動作」。',
    ROBOT_BUSY: '機器正在執行其他動作，請先停止或稍後再試。',
    ROBOT_UNAVAILABLE: '機器控制尚未就緒，請確認 Zenbo App 已開啟。',
    ROBOT_INITIALIZING: '機器正在準備，請稍後再試。',
    PAIRING_EXPIRED: '配對 QR 已過期，請在 Zenbo 重新產生配對 QR。',
    INVALID_PIN: '管理 PIN 不正確，請重新輸入。',
    ARTIFACT_EXPIRED: '照片已失效，請重新拍照。',
  };
  return messages[error?.code] || (error?.name === 'AbortError' ? '連線逾時，請稍後再試。' : '操作未完成，請確認機器狀態後再試。');
}
