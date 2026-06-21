// Java String.hashCode, so client and server agree on the document hash used
// for drift detection / resync. Math.imul keeps the 31*h multiply in 32-bit.
export function strHash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) {
    h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
  }
  return h;
}
