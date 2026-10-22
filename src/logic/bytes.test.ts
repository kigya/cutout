import { base64ToBytes, bytesToBase64 } from './bytes';

describe('bytes', () => {
  test('round-trips alpha bytes, including an empty mask', () => {
    expect(Array.from(base64ToBytes(bytesToBase64(new Uint8Array())))).toEqual([]);
    const bytes = Uint8Array.from([0, 8, 9, 255]);
    expect(Array.from(base64ToBytes(bytesToBase64(bytes)))).toEqual([0, 8, 9, 255]);
  });
});
