declare module '*.css';

declare module '*.png' {
  const src: string;
  export default src;
}

declare module '*.svg' {
  const src: string;
  export default src;
}

declare module '*.webp' {
  const src: string;
  export default src;
}

declare const process: {
  readonly env: {
    readonly NODE_ENV: string;
    readonly REACT_APP_POSTHOG_KEY: string | undefined;
    readonly REACT_APP_POSTHOG_HOST: string | undefined;
  };
};
