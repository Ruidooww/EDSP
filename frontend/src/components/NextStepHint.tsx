import { Alert } from 'antd';

interface NextStepHintProps {
  message?: string;
  description: string;
  type?: 'info' | 'success' | 'warning' | 'error';
}

export default function NextStepHint({
  message = '下一步',
  description,
  type = 'info',
}: NextStepHintProps) {
  return (
    <Alert
      className="next-step-hint"
      type={type}
      showIcon
      message={message}
      description={description}
    />
  );
}
