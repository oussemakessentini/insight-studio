import { ApiError } from '../api/client'

// A dashboard runs one chart per widget. The server runs at most 6 chart queries per business at
// once and answers 429 (no work started) beyond that (docs/dashboards-contract.md §4), so the page
// sends at most a few at a time and retries a 429 after its Retry-After.

interface Job {
  start: () => void
  signal: AbortSignal
}

/** Runs tasks with at most `limit` in progress; others wait in order. */
export class RequestQueue {
  private readonly limit: number
  /** How often one task is retried after 429 before its error is shown. */
  private readonly maxRetries: number
  private active = 0
  private readonly waiting: Job[] = []

  constructor(limit: number, maxRetries = 20) {
    this.limit = limit
    this.maxRetries = maxRetries
  }

  /**
   * Runs `task` when a slot is free. A 429 frees the slot, waits `Retry-After` seconds (1 when
   * absent) and puts the task back at the front of the line. Aborting `signal` drops a waiting
   * task and rejects with an AbortError.
   */
  run<T>(task: (signal: AbortSignal) => Promise<T>, signal: AbortSignal): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      let retries = 0
      let timer: ReturnType<typeof setTimeout> | undefined

      const job: Job = {
        signal,
        start: () => {
          this.active += 1
          task(signal)
            .then(resolve, (error: unknown) => {
              if (error instanceof ApiError && error.status === 429 && !signal.aborted && retries < this.maxRetries) {
                retries += 1
                timer = setTimeout(() => this.enqueue(job, true), (error.retryAfterSeconds ?? 1) * 1000)
                return
              }
              reject(error)
            })
            .finally(() => {
              this.active -= 1
              this.pump()
            })
        },
      }

      signal.addEventListener(
        'abort',
        () => {
          clearTimeout(timer)
          const index = this.waiting.indexOf(job)
          if (index >= 0) this.waiting.splice(index, 1)
          reject(new DOMException('Aborted', 'AbortError'))
        },
        { once: true },
      )
      if (signal.aborted) {
        reject(new DOMException('Aborted', 'AbortError'))
        return
      }
      this.enqueue(job, false)
    })
  }

  private enqueue(job: Job, front: boolean): void {
    if (job.signal.aborted) return
    if (front) this.waiting.unshift(job)
    else this.waiting.push(job)
    this.pump()
  }

  private pump(): void {
    while (this.active < this.limit && this.waiting.length > 0) {
      this.waiting.shift()!.start()
    }
  }
}

/** Every dashboard widget's chart data goes through this one queue: at most 3 requests at a time. */
export const chartDataQueue = new RequestQueue(3)
