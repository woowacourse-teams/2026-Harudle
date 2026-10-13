const CANONICAL_UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const isCanonicalUuid = (value: unknown): value is string => {
  return typeof value === 'string' && CANONICAL_UUID_PATTERN.test(value);
};
