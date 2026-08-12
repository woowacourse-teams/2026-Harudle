declare module '*.css';

declare module '*.png' {
  const src: string;
  export default src;
}

declare module '*.svg' {
  const src: string;
  export default src;
}

declare const process: {
  readonly env: {
    readonly NODE_ENV: string;
  };
};
