export type SavePhase = 'idle' | 'saving' | 'saved' | 'failed';

export function savePhase(status: string): 'saved' | 'failed' {
  return status === 'saved' ? 'saved' : 'failed';
}
