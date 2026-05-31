/**
 * EDSP 安全运营平台 - Ant Design v5 主题配置
 * 不修改任何业务逻辑，仅覆盖设计 token。
 */
import type { ThemeConfig } from 'antd';

const edspTheme: ThemeConfig = {
  token: {
    // 主色：安全青绿
    colorPrimary: '#0f9f9a',

    // 语义色
    colorSuccess: '#17b77a',
    colorWarning: '#f77234',
    colorError: '#e84545',
    colorInfo: '#3d81f5',

    // 基础
    borderRadius: 6,
    fontSize: 14,
    fontFamily: '"Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", Arial, sans-serif',
  },
  components: {
    Layout: {
      bodyBg: '#f0f4f8',
      siderBg: '#0d1421',
      triggerBg: '#0d1421',
    },
    Menu: {
      darkItemBg: '#0d1421',
      darkSubMenuItemBg: '#111928',
      darkItemSelectedBg: 'rgba(15, 159, 154, 0.15)',
      darkItemSelectedColor: '#0f9f9a',
      darkItemColor: '#c8d4e3',
      darkItemHoverColor: '#e6f9f7',
      darkItemHoverBg: 'rgba(15, 159, 154, 0.08)',
    },
    Card: {
      borderRadiusLG: 8,
    },
    Table: {
      headerBg: '#f4f7fb',
      rowHoverBg: '#f0fbf9',
      cellPaddingBlock: 11,
    },
    Button: {
      borderRadius: 6,
    },
    Input: {
      borderRadius: 6,
    },
    Select: {
      borderRadius: 6,
    },
    Tag: {
      borderRadiusSM: 4,
    },
  },
};

export default edspTheme;
