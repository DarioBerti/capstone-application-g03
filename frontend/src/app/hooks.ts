import { useDispatch, useSelector } from 'react-redux'
import type { AppDispatch, RootState } from './store'

// Pre-typed Redux hooks (react-redux v9 `.withTypes`) — use these instead of the plain ones.
export const useAppDispatch = useDispatch.withTypes<AppDispatch>()
export const useAppSelector = useSelector.withTypes<RootState>()
