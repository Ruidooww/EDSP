import { Typography } from 'antd';
import type { ReactNode } from 'react';

interface IngestionPlanDetailSectionProps {
  title: string;
  children: ReactNode;
}

export default function IngestionPlanDetailSection({ title, children }: IngestionPlanDetailSectionProps) {
  return (
    <div style={{ minWidth: 0 }}>
      <Typography.Text strong>{title}</Typography.Text>
      <div style={{ marginTop: 8, color: '#4b5565', lineHeight: 1.7, wordBreak: 'break-word' }}>
        {children}
      </div>
    </div>
  );
}
