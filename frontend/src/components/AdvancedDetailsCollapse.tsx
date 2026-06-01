import { Collapse, Descriptions, Typography } from 'antd';
import type { ReactNode } from 'react';
import { technicalValue } from '../utils/businessDisplay';

export interface AdvancedDetailItem {
  label: ReactNode;
  value: unknown;
  span?: number;
  code?: boolean;
}

interface AdvancedDetailsCollapseProps {
  title?: string;
  note?: ReactNode;
  items?: AdvancedDetailItem[];
  children?: ReactNode;
}

export default function AdvancedDetailsCollapse({
  title = '技术详情',
  note = '以下内容面向管理员和排障人员，默认收起，不影响日常业务操作。',
  items,
  children,
}: AdvancedDetailsCollapseProps) {
  const content = children ?? (
    <Descriptions bordered size="small" column={1}>
      {(items ?? []).map((item, index) => (
        <Descriptions.Item key={index} label={item.label} span={item.span}>
          {item.code ? (
            <Typography.Text code style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
              {technicalValue(item.value)}
            </Typography.Text>
          ) : technicalValue(item.value)}
        </Descriptions.Item>
      ))}
    </Descriptions>
  );

  return (
    <Collapse
      className="advanced-details-collapse"
      items={[
        {
          key: 'advanced-details',
          label: title,
          children: (
            <div className="advanced-details-body">
              {note ? <Typography.Paragraph type="secondary">{note}</Typography.Paragraph> : null}
              {content}
            </div>
          ),
        },
      ]}
    />
  );
}
