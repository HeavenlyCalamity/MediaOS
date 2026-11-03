import { describe, expect, it } from 'vitest';
import { approvalReminder, brands } from './App';

describe('dashboard scaffold', () => {
  it('contains both provisional brands and the human approval reminder', () => {
    expect(brands.map((brand) => brand.name)).toEqual(['Cosmic Curiosity', 'History Uncovered']);
    expect(approvalReminder).toContain('review before it can be published');
  });
});
