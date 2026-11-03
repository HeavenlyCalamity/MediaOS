export function splitCommaList(value: string): string[] {
  return value.split(',').map((item) => item.trim()).filter(Boolean);
}

export const approvalReminder = 'External publishing requires your review and approval of the exact content version.';
