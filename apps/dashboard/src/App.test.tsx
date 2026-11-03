import { describe, expect, it } from 'vitest';
import { approvalReminder, splitCommaList } from './domain';

describe('dynamic dashboard configuration', () => {
  it('parses user-defined categories and content pillars without a fixed taxonomy', () => {
    expect(splitCommaList('Gaming, food, Custom category')).toEqual(['Gaming', 'food', 'Custom category']);
    expect(splitCommaList('  music ,, storytelling  ')).toEqual(['music', 'storytelling']);
  });

  it('retains the explicit approval requirement for future publishing', () => {
    expect(approvalReminder).toContain('review and approval');
  });
});
