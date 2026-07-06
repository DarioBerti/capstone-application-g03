import { formatTime } from '../../lib/format'

// The signature "station-to-station" element: departure time/place, a connecting line with a hollow origin
// dot and a solid destination dot, and the journey duration. Our catalog trips are direct point-to-point,
// so the label is always DIRECT (no connection model in the domain).
export default function StationLine({
  departTime,
  departPlace,
  arriveTime,
  arrivePlace,
  duration,
  date,
}: {
  departTime: string
  departPlace: string
  arriveTime: string
  arrivePlace: string
  duration: string
  date?: string
}) {
  return (
    <div className="flex items-center justify-between gap-md">
      <div className="text-left">
        <p className="font-label-tabular text-label-tabular tabular-nums">{formatTime(departTime)}</p>
        <p className="font-body-sm text-body-sm text-on-surface-variant">{departPlace}</p>
      </div>
      <div className="flex-grow flex flex-col items-center px-md">
        {date && <p className="font-label-tabular text-body-sm text-on-surface-variant mb-xs tabular-nums">{date}</p>}
        <p className="font-label-caps text-label-caps text-on-surface-variant mb-xs">{duration}</p>
        <div className="w-full h-[2px] bg-outline-variant relative flex items-center justify-between">
          <div className="w-3 h-3 rounded-full border-2 border-primary bg-surface-container-lowest" />
          <div className="w-3 h-3 rounded-full bg-on-background" />
        </div>
        <p className="font-label-caps text-label-caps text-primary mt-xs">DIRECT</p>
      </div>
      <div className="text-right">
        <p className="font-label-tabular text-label-tabular tabular-nums">{formatTime(arriveTime)}</p>
        <p className="font-body-sm text-body-sm text-on-surface-variant">{arrivePlace}</p>
      </div>
    </div>
  )
}
