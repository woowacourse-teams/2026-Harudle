import { createHash } from "node:crypto";

export const MAX_BYTES = 20 * 1024 * 1024;
const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);

export class WorkerError extends Error {
  constructor(status, code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}

export function validateInput(input) {
  if (!input || typeof input !== "object" || Array.isArray(input)) throw new WorkerError(400, "invalid_input");
  for (const field of ["systemInstruction", "referenceInstruction", "finalTask"]) {
    if (typeof input[field] !== "string" || !input[field].trim()
        || Buffer.byteLength(input[field]) > 256 * 1024) throw new WorkerError(400, "invalid_prompt");
  }
  const mediaType = input.reference?.mediaType;
  const extensions = { "image/png": "png", "image/jpeg": "jpg", "image/webp": "webp" };
  const base64 = input.reference?.base64;
  if (!extensions[mediaType]) throw new WorkerError(415, "unsupported_reference_type");
  if (typeof base64 !== "string" || !base64.length || base64.length > MAX_BYTES
      || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(base64)) {
    throw new WorkerError(400, "invalid_reference");
  }
  const reference = Buffer.from(base64, "base64");
  if (!reference.length) throw new WorkerError(400, "invalid_reference");
  return {
    prompt: [input.systemInstruction, input.referenceInstruction, input.finalTask].join("\n\n"),
    reference, extension: extensions[mediaType],
  };
}

export function validatePng(bytes, reference) {
  if (!Buffer.isBuffer(bytes) || bytes.length < 33 || bytes.length > MAX_BYTES
      || !bytes.subarray(0, 8).equals(signature)
      || !bytes.readUInt32BE(16) || !bytes.readUInt32BE(20)
      || reference && createHash("sha256").update(bytes).digest("hex")
        === createHash("sha256").update(reference).digest("hex")) {
    throw new WorkerError(502, "invalid_generated_image");
  }
  return bytes;
}
