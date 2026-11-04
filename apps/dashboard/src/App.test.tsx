import { describe, expect, it } from 'vitest';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { approvalReminder, splitCommaList } from './domain';
import ReviewWorkspace from './ReviewWorkspace';

describe('dynamic dashboard configuration', () => {
  it('parses user-defined categories and content pillars without a fixed taxonomy', () => {
    expect(splitCommaList('Gaming, food, Custom category')).toEqual(['Gaming', 'food', 'Custom category']);
    expect(splitCommaList('  music ,, storytelling  ')).toEqual(['music', 'storytelling']);
  });

  it('retains the explicit approval requirement for future publishing', () => {
    expect(approvalReminder).toContain('review and approval');
  });

  it('renders the accessible Stage 3 review workspace and dynamic filters', () => {
    const html = renderToStaticMarkup(createElement(ReviewWorkspace, {
      brands: [{ id: 'brand-1', name: 'Test brand' }],
      categories: [{ id: 'category-1', name: 'User category', parentCategoryId: null }],
      onContentUpdated: async () => undefined,
    }));
    expect(html).toContain('Review studio');
    expect(html).toContain('REVIEW QUEUE');
    expect(html).toContain('User category');
    expect(html).toContain('Select content to review');
    expect(html).toContain('All statuses');
  });
});
