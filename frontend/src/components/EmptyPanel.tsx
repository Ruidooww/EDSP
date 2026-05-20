import { Empty } from 'antd';

interface EmptyPanelProps {
  title: string;
  description: string;
}

export default function EmptyPanel({ title, description }: EmptyPanelProps) {
  return (
    <div className="empty-panel">
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={
          <span>
            <strong>{title}</strong>
            <br />
            {description}
          </span>
        }
      />
    </div>
  );
}
