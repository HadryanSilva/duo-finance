const HEADER =
  'Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance'

const pad = (n: number) => String(n).padStart(2, '0')

function stamp(date: Date, hour: number): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(hour)}:00:00`
}

function daysAgo(days: number): Date {
  const d = new Date()
  d.setDate(d.getDate() - days)
  return d
}

export type RevolutSample = {
  /** The CSV file's content. */
  csv: string
  /** Descriptions of the COMPLETED rows, which the import creates. */
  completed: string[]
  /** Description of the PENDING row, which the import skips. */
  pending: string
}

/**
 * A small Revolut statement dated relative to today and kept inside the current month (the
 * transactions page opens on it): three COMPLETED EUR rows (two expenses, one income) and one
 * PENDING row. Early in the month the older rows fall back to today.
 */
export function revolutSample(tag: string): RevolutSample {
  const today = new Date()
  const offset = (days: number) => daysAgo(Math.min(days, today.getDate() - 1))
  const rows = [
    {
      type: 'CARD_PAYMENT',
      at: stamp(offset(2), 10),
      name: `Padaria ${tag}`,
      amount: '-12.50',
      state: 'COMPLETED',
    },
    {
      type: 'CARD_PAYMENT',
      at: stamp(offset(1), 12),
      name: `Cinema ${tag}`,
      amount: '-30.00',
      state: 'COMPLETED',
    },
    {
      type: 'TOPUP',
      at: stamp(offset(0), 9),
      name: `Salario ${tag}`,
      amount: '1500.00',
      state: 'COMPLETED',
    },
    {
      type: 'CARD_PAYMENT',
      at: stamp(offset(0), 15),
      name: `Farmacia ${tag}`,
      amount: '-8.90',
      state: 'PENDING',
    },
  ]
  const lines = rows.map(
    (r) =>
      `${r.type},Current,${r.at},${r.state === 'PENDING' ? '' : r.at},${r.name},${r.amount},0.00,EUR,${r.state},100.00`,
  )
  return {
    csv: [HEADER, ...lines].join('\n') + '\n',
    completed: rows.filter((r) => r.state === 'COMPLETED').map((r) => r.name),
    pending: `Farmacia ${tag}`,
  }
}
