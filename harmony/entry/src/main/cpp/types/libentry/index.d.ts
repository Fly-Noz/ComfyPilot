export const nativeVersion: () => number;
export const nativeSelfTest: () => string;
export const nativeSessionCreate: (configJson: string) => number;
export const nativeSessionPump: (nowMs: number) => string;
export const nativeSessionSend: (messageJson: string) => number;
export const nativeSessionWorkflow: (requestJson: string) => string;
export const nativeSessionDestroy: () => void;
