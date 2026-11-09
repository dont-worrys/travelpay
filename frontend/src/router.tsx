import { createBrowserRouter } from 'react-router'
import { AppLayout } from './components/AppLayout'
import { RequireAuth } from './features/auth/RequireAuth'
import { HomePage } from './pages/HomePage'
import { LoginPage } from './pages/LoginPage'
import { MyPage } from './pages/MyPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { SignupPage } from './pages/SignupPage'

// 송금(4단계)·지갑(5단계) 화면은 RequireAuth 아래에 경로를 추가한다.
export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'login', element: <LoginPage /> },
      { path: 'signup', element: <SignupPage /> },
      {
        element: <RequireAuth />,
        children: [{ path: 'me', element: <MyPage /> }],
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
