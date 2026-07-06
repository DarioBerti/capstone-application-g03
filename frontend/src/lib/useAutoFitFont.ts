import { useLayoutEffect, useRef, useState } from 'react'

// Shrinks an element's font size just enough that `text` fits its content box, down to `minPx`.
// Measures with a canvas at `maxPx` using the element's own font (so the result is independent of the
// currently-applied size and can't oscillate), and re-measures on width or web-font changes.
export function useAutoFitFont<T extends HTMLElement>(text: string, maxPx: number, minPx = 12) {
  const ref = useRef<T>(null)
  const [fontPx, setFontPx] = useState(maxPx)

  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const ctx = document.createElement('canvas').getContext('2d')
    if (!ctx) return

    const measure = () => {
      const style = getComputedStyle(el)
      const available = el.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight)
      if (available <= 0) return
      ctx.font = `${style.fontWeight} ${maxPx}px ${style.fontFamily}`
      const width = ctx.measureText(text).width
      setFontPx(width > available ? Math.max(minPx, Math.floor((maxPx * available) / width)) : maxPx)
    }

    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(el)
    // Web fonts load async; a measurement taken before Inter is ready is wrong — redo once fonts settle.
    document.fonts?.ready.then(measure).catch(() => {})
    return () => observer.disconnect()
  }, [text, maxPx, minPx])

  return { ref, fontPx }
}
