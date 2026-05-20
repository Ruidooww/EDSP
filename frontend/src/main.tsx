import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import App from './App';
import './styles.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: '#137c72',
          borderRadius: 6,
          fontSize: 14,
        },
        components: {
          Layout: {
            bodyBg: '#f6f7f8',
            siderBg: '#18201f',
            triggerBg: '#18201f',
          },
          Menu: {
            darkItemBg: '#18201f',
            darkSubMenuItemBg: '#131918',
            darkItemSelectedBg: '#137c72',
          },
          Card: {
            borderRadiusLG: 6,
          },
        },
      }}
    >
      <App />
    </ConfigProvider>
  </React.StrictMode>,
);
