import { savePhase } from './save';

describe('save', () => {
  test('gallery success and failure are distinct', () => {
    expect(savePhase('saved')).toBe('saved');
    expect(savePhase('failed')).toBe('failed');
    expect(savePhase('')).toBe('failed');
  });
});
