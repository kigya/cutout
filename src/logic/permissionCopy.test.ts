import { permissionCopy } from './permissionCopy';
import { permissionKinds } from './permissions';

describe('permission copy', () => {
  test('each permission has a title and an explanation', () => {
    for (const kind of permissionKinds()) {
      const copy = permissionCopy(kind);
      expect(copy.title.length).toBeGreaterThan(0);
      expect(copy.body.length).toBeGreaterThan(0);
    }
  });
});
