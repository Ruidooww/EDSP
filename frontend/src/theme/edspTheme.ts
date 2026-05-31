/**
 * EDSP 安全运营平台 - Ant Design v5 主题配置
 * 白色简洁风格：白色侧边栏 + 青绿选中态 + 极浅灰内容区
 * 不修改任何业务逻辑，仅覆盖设计 token。
 */
import type { ThemeConfig } from 'antd';

const edspTheme: ThemeConfig = {
  token: {
    colorPrimary: '#0f9f9a',
    colorSuccess: '#17b77a',
    colorWarning: '#f77234',
    colorError: '#e84545',
    colorInfo: '#3d81f5',
    borderRadius: 6,
    fontSize: 14,
    fontFamily: '"Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", Arial, sans-serif',
  },
  components: {
    Layout: {
      bodyBg: '#f7f8fa',
      siderBg: '#ffffff',
      triggerBg: '#f5f6f8',
    },
    Menu: {
      itemBg: 'transparent',
      subMenuItemBg: 'transparent',
      itemColor: '#6b7280',
      itemHoverColor: '#1c1f26',
      itemHoverBg: '#f5f6f8',
      itemSelectedColor: '#ffffff',
      itemSelectedBg: '#0f9f9a',
      itemActiveBg: '#0d8580',
    },
    Card: {
      borderRadiusLG: 9,
    },
    Table: {
      headerBg: '#f7f8fa',
      rowHoverBg: '#f0fbf9',
      cellPaddingBlock: 11,
      borderColor: '#f0f1f4',
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
