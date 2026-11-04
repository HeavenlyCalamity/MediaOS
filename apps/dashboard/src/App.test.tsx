import { describe, expect, it } from 'vitest';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { approvalReminder, splitCommaList } from './domain';
import ReviewWorkspace from './ReviewWorkspace';
import ProductionWorkspace from './ProductionWorkspace';

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

  it('labels Stage 4 as local synthetic production and keeps formats user defined', () => {
    const html = renderToStaticMarkup(createElement(ProductionWorkspace, {
      brands: [{ id: 'brand-1', name: 'Test brand' }],
      profiles: [{ id: 'profile-1', brandId: 'brand-1', name: 'Podcast clips', categoryId: null, contentFormat: 'podcast clip', settings: {} }],
      content: [], onContentUpdated: async () => undefined,
    }));
    expect(html).toContain('Production workspace');
    expect(html).toContain('Mock output is synthetic');
    expect(html).toContain('without live web research');
    expect(html).toContain('synthetic test assets');
  });
});
