import { Tag } from 'antd';
import type { ReactNode } from 'react';
import type { StatusDisplay } from '../utils/businessDisplay';

interface BusinessStatusTagProps {
  status: StatusDisplay;
  icon?: ReactNode;
}

export default function BusinessStatusTag({ status, icon }: BusinessStatusTagProps) {
  return (
    <Tag color={status.color} icon={icon} style={{ whiteSpace: 'normal', wordBreak: 'break-word' }}>
      {status.label}
    </Tag>
  );
}
