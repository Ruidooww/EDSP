import { Alert, Button, Drawer, Form, Input, Select, Segmented, Space, Table, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useEffect, useMemo, useState } from 'react';
import type { IngestionPlanMappingRuleUpdateRequest } from '../../../types';
import { STANDARD_FIELD_LABELS } from '../utils/ingestionPlanLabels';
import type { NormalizedPlanMapping } from '../utils/normalizeIngestionPlan';

type RuleKind = 'none' | 'trim' | 'lower' | 'upper' | 'defaultIfBlank' | 'valueMap';
type OnMissing = 'keepOriginal' | 'useDefault';

interface ValueMapRow {
  id: string;
  sourceValue: string;
  mappedValue: string;
}

interface IngestionPlanRuleConfigDrawerProps {
  open: boolean;
  mapping: NormalizedPlanMapping | null;
  active: boolean;
  saving: boolean;
  onClose: () => void;
  onSave: (payload: IngestionPlanMappingRuleUpdateRequest) => Promise<void>;
}

function ruleKind(rule?: string): RuleKind {
  if (!rule) {
    return 'none';
  }
  if (rule.startsWith('defaultIfBlank:')) {
    return 'defaultIfBlank';
  }
  if (rule === 'trim' || rule === 'lower' || rule === 'upper' || rule === 'valueMap') {
    return rule;
  }
  return 'none';
}

function defaultIfBlankValue(rule?: string) {
  return rule?.startsWith('defaultIfBlank:') ? rule.slice('defaultIfBlank:'.length) : '';
}

function valueRows(mapping: NormalizedPlanMapping | null): ValueMapRow[] {
  const values = mapping?.transformRulePayload?.values;
  if (!values || typeof values !== 'object' || Array.isArray(values)) {
    return [];
  }
  return Object.entries(values)
    .filter((entry): entry is [string, string] => typeof entry[1] === 'string')
    .map(([sourceValue, mappedValue], index) => ({
      id: `${sourceValue}-${index}`,
      sourceValue,
      mappedValue,
    }));
}

function nextRowId() {
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function fieldLabel(field?: string) {
  return field ? STANDARD_FIELD_LABELS[field] || field : '-';
}

export default function IngestionPlanRuleConfigDrawer({
  open,
  mapping,
  active,
  saving,
  onClose,
  onSave,
}: IngestionPlanRuleConfigDrawerProps) {
  const [selectedRule, setSelectedRule] = useState<RuleKind>('none');
  const [defaultValue, setDefaultValue] = useState('');
  const [rows, setRows] = useState<ValueMapRow[]>([]);
  const [onMissing, setOnMissing] = useState<OnMissing>('keepOriginal');
  const [missingDefaultValue, setMissingDefaultValue] = useState('');

  useEffect(() => {
    if (!open || !mapping) {
      return;
    }
    setSelectedRule(ruleKind(mapping.transformRule));
    setDefaultValue(defaultIfBlankValue(mapping.transformRule));
    setRows(valueRows(mapping));
    setOnMissing(mapping.transformRulePayload?.onMissing === 'useDefault' ? 'useDefault' : 'keepOriginal');
    setMissingDefaultValue(typeof mapping.transformRulePayload?.defaultValue === 'string'
      ? mapping.transformRulePayload.defaultValue
      : '');
  }, [mapping, open]);

  const duplicateKeys = useMemo(() => {
    const seen = new Set<string>();
    const duplicates = new Set<string>();
    for (const row of rows) {
      if (!row.sourceValue) {
        continue;
      }
      if (seen.has(row.sourceValue)) {
        duplicates.add(row.sourceValue);
      }
      seen.add(row.sourceValue);
    }
    return duplicates;
  }, [rows]);

  function updateRow(id: string, patch: Partial<ValueMapRow>) {
    setRows((current) => current.map((row) => row.id === id ? { ...row, ...patch } : row));
  }

  function removeRow(id: string) {
    setRows((current) => current.filter((row) => row.id !== id));
  }

  function validateValueMap() {
    if (rows.length > 200) {
      message.error('值映射最多支持 200 条。');
      return false;
    }
    for (const row of rows) {
      if (!row.sourceValue) {
        message.error('请输入来源值。');
        return false;
      }
      if (!row.mappedValue) {
        message.error('请输入转换后的值。');
        return false;
      }
      if (row.sourceValue.length > 200) {
        message.error('来源值不能超过 200 个字符。');
        return false;
      }
      if (row.mappedValue.length > 500) {
        message.error('转换后的值不能超过 500 个字符。');
        return false;
      }
    }
    if (duplicateKeys.size) {
      message.error('来源值不能重复。');
      return false;
    }
    if (onMissing === 'useDefault' && !missingDefaultValue) {
      message.error('请选择兜底值时必须填写默认值。');
      return false;
    }
    if (missingDefaultValue.length > 500) {
      message.error('默认值不能超过 500 个字符。');
      return false;
    }
    return true;
  }

  async function save() {
    if (!mapping) {
      return;
    }
    if (active) {
      message.warning('已启用方案不能直接修改，请先生成新草稿。');
      return;
    }

    let transformRule: string | null = selectedRule;
    let transformRulePayload: IngestionPlanMappingRuleUpdateRequest['transformRulePayload'] = null;
    if (selectedRule === 'none') {
      transformRule = null;
    } else if (selectedRule === 'defaultIfBlank') {
      if (!defaultValue) {
        message.error('请输入默认值。');
        return;
      }
      transformRule = `defaultIfBlank:${defaultValue}`;
    } else if (selectedRule === 'valueMap') {
      if (!validateValueMap()) {
        return;
      }
      const values = Object.fromEntries(rows.map((row) => [row.sourceValue, row.mappedValue]));
      transformRulePayload = {
        type: 'valueMap',
        values,
        onMissing,
        ...(onMissing === 'useDefault' ? { defaultValue: missingDefaultValue } : {}),
      };
    }

    await onSave({
      sourceField: mapping.sourceField,
      standardField: mapping.standardField,
      transformRule,
      transformRulePayload,
    });
    onClose();
  }

  const columns: ColumnsType<ValueMapRow> = [
    {
      title: '来源值',
      dataIndex: 'sourceValue',
      render: (_, row) => (
        <Input
          value={row.sourceValue}
          status={duplicateKeys.has(row.sourceValue) ? 'error' : undefined}
          maxLength={200}
          onChange={(event) => updateRow(row.id, { sourceValue: event.target.value })}
        />
      ),
    },
    {
      title: '转换后的值',
      dataIndex: 'mappedValue',
      render: (_, row) => (
        <Input
          value={row.mappedValue}
          maxLength={500}
          onChange={(event) => updateRow(row.id, { mappedValue: event.target.value })}
        />
      ),
    },
    {
      title: '',
      width: 88,
      render: (_, row) => (
        <Button danger type="text" onClick={() => removeRow(row.id)}>
          移除
        </Button>
      ),
    },
  ];

  return (
    <Drawer
      title="配置字段转换规则"
      width={720}
      open={open}
      onClose={onClose}
      destroyOnClose
      extra={(
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={saving} disabled={active} onClick={save}>
            保存规则
          </Button>
        </Space>
      )}
    >
      {active && (
        <Alert
          showIcon
          type="warning"
          message="已启用方案不能直接修改"
          description="请先生成新草稿，再调整字段转换规则。"
          style={{ marginBottom: 16 }}
        />
      )}
      <Alert
        showIcon
        type="info"
        message="字段转换规则只影响当前推荐接入方案。"
        description="保存后请重新执行试运行，验证通过后再启用方案。"
        style={{ marginBottom: 16 }}
      />
      <Form layout="vertical">
        <Form.Item label="字段映射">
          <Space size={[8, 8]} wrap>
            <Typography.Text code>{fieldLabel(mapping?.sourceField)}</Typography.Text>
            <Typography.Text>转换为</Typography.Text>
            <Typography.Text code>{fieldLabel(mapping?.standardField)}</Typography.Text>
          </Space>
        </Form.Item>
        <Form.Item label="转换方式">
          <Segmented<RuleKind>
            value={selectedRule}
            onChange={setSelectedRule}
            options={[
              { label: '不转换', value: 'none' },
              { label: '去除空格', value: 'trim' },
              { label: '转小写', value: 'lower' },
              { label: '转大写', value: 'upper' },
              { label: '空值兜底', value: 'defaultIfBlank' },
              { label: '值映射', value: 'valueMap' },
            ]}
          />
        </Form.Item>

        {selectedRule === 'defaultIfBlank' && (
          <Form.Item label="默认值" required>
            <Input
              value={defaultValue}
              maxLength={500}
              onChange={(event) => setDefaultValue(event.target.value)}
            />
          </Form.Item>
        )}

        {selectedRule === 'valueMap' && (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            {!rows.length && (
              <Alert
                showIcon
                type="warning"
                message="尚未配置值映射"
                description="未列出的来源值会按照下方兜底策略处理。"
              />
            )}
            <Table<ValueMapRow>
              size="small"
              rowKey="id"
              columns={columns}
              dataSource={rows}
              pagination={false}
              scroll={{ x: 560 }}
            />
            <Button
              onClick={() => setRows((current) => [...current, { id: nextRowId(), sourceValue: '', mappedValue: '' }])}
              disabled={rows.length >= 200}
            >
              新增映射
            </Button>
            <Form.Item label="未匹配值处理">
              <Select<OnMissing>
                value={onMissing}
                options={[
                  { label: '保留原值', value: 'keepOriginal' },
                  { label: '使用默认值', value: 'useDefault' },
                ]}
                onChange={setOnMissing}
              />
            </Form.Item>
            {onMissing === 'useDefault' && (
              <Form.Item label="默认值" required>
                <Input
                  value={missingDefaultValue}
                  maxLength={500}
                  onChange={(event) => setMissingDefaultValue(event.target.value)}
                />
              </Form.Item>
            )}
          </Space>
        )}
      </Form>
    </Drawer>
  );
}
