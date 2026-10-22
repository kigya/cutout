import { canEdit, type EditSession } from './Erase';

describe('canEdit', () => {
  const files = { originalPath: '/a', cutoutPath: '/b', width: 2, height: 2 };

  test('a session can be edited when the files are present', () => {
    expect(canEdit(null)).toBe(false);
    expect(canEdit({})).toBe(false);
    expect(canEdit({ originalPath: '/a' })).toBe(false);
    expect(canEdit({ originalPath: '/a', cutoutPath: '/b' })).toBe(false);
    expect(canEdit({ originalPath: '/a', cutoutPath: '/b', width: 2 })).toBe(false);
    expect(canEdit({ ...files, width: 0 })).toBe(false);
    expect(canEdit({ ...files, status: 'failed' })).toBe(false);
    expect(canEdit({ ...files, status: 'svg' })).toBe(false);
    expect(canEdit(files as EditSession)).toBe(true);
    expect(canEdit({ ...files, status: 'ready' })).toBe(true);
    expect(canEdit({ ...files, status: 'manual' })).toBe(true);
  });
});
