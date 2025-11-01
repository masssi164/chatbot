const APP_TAG = "[chatbot]";

function format(message: unknown, ...optional: unknown[]) {
  if (typeof message === "string") {
    return [`${APP_TAG} ${message}`, ...optional];
  }
  return [APP_TAG, message, ...optional];
}

export const logger = {
  info(message: unknown, ...optional: unknown[]) {
    console.info(...format(message, ...optional));
  },
  warn(message: unknown, ...optional: unknown[]) {
    console.warn(...format(message, ...optional));
  },
  error(message: unknown, ...optional: unknown[]) {
    console.error(...format(message, ...optional));
  },
  debug(message: unknown, ...optional: unknown[]) {
    if (import.meta.env.DEV) {
      console.debug(...format(message, ...optional));
    }
  },
};

export default logger;
