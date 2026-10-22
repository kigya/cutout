import { act, renderHook } from '@testing-library/react-native';
import { useDisplayedProgress } from './useDisplayedProgress';

type ProgressProps = { percent: number; active: boolean; id?: string };

beforeEach(() => {
  jest.useFakeTimers();
  jest.spyOn(globalThis, 'setInterval');
  jest.spyOn(globalThis, 'clearInterval');
});
afterEach(() => { jest.restoreAllMocks(); jest.useRealTimers(); });

function lastInterval() {
  return (setInterval as jest.Mock).mock.results.at(-1)!.value;
}

test('advances throughout a long inference, follows milestones, and holds completion until native success', async () => {
  const hook = await renderHook<number, ProgressProps>(({ percent, active }) => useDisplayedProgress(percent, active), {
    initialProps: { percent: 28, active: true },
  });
  expect(hook.result.current).toBe(28);
  await act(() => jest.advanceTimersByTime(4000));
  const first = hook.result.current;
  expect(first).toBeGreaterThan(28);
  await act(() => jest.advanceTimersByTime(12000));
  expect(hook.result.current).toBeGreaterThan(first);
  expect(hook.result.current).toBe(59);
  await hook.rerender({ percent: 18, active: true });
  await act(() => jest.advanceTimersByTime(1000));
  expect(hook.result.current).toBe(59);
  await hook.rerender({ percent: 92, active: true });
  expect(hook.result.current).toBe(92);
  await act(() => jest.advanceTimersByTime(2500));
  expect(hook.result.current).toBe(95);
  const exportTimer = lastInterval();
  await hook.rerender({ percent: 100, active: true });
  expect(hook.result.current).toBe(98);
  expect(clearInterval).toHaveBeenCalledWith(exportTimer);
  await hook.rerender({ percent: 100, active: false });
  expect(hook.result.current).toBe(100);
});

test('resets on cancellation, retry and a new bubble task, and disposes timers on unmount', async () => {
  const hook = await renderHook<number, ProgressProps>(({ percent, active, id }) => useDisplayedProgress(percent, active, id), {
    initialProps: { percent: 28, active: true, id: 'first' },
  });
  await act(() => jest.advanceTimersByTime(16000));
  expect(hook.result.current).toBe(59);
  await hook.rerender({ percent: 8, active: true, id: 'second' });
  expect(hook.result.current).toBe(8);
  await act(() => jest.advanceTimersByTime(9000));
  expect(hook.result.current).toBe(17);
  const cancelledTimer = lastInterval();
  await hook.rerender({ percent: 0, active: false, id: 'second' });
  expect(hook.result.current).toBe(0);
  expect(clearInterval).toHaveBeenCalledWith(cancelledTimer);
  await hook.rerender({ percent: 28, active: true, id: 'second' });
  expect(hook.result.current).toBe(28);
  const retryTimer = lastInterval();
  await hook.unmount();
  expect(clearInterval).toHaveBeenCalledWith(retryTimer);
});

test('clamps malformed native values and keeps completed idle tasks timer-free', async () => {
  const hook = await renderHook<number, ProgressProps>(({ percent, active }) => useDisplayedProgress(percent, active), {
    initialProps: { percent: NaN, active: false },
  });
  expect(hook.result.current).toBe(0);
  expect(setInterval).not.toHaveBeenCalled();
  await hook.rerender({ percent: 200, active: true });
  expect(hook.result.current).toBe(98);
  expect(setInterval).not.toHaveBeenCalled();
});
