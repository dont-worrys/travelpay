import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router'
import { AuthProvider } from './features/auth/AuthContext'
import { router } from './router'
import './index.css'

const root = document.getElementById('root')
if (!root) {
  throw new Error('#root 요소가 없습니다.')
}

createRoot(root).render(
  <StrictMode>
    <AuthProvider>
      <RouterProvider router={router} />
    </AuthProvider>
  </StrictMode>,
)
