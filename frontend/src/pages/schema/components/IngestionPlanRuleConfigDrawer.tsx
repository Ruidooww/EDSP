import { Alert, Button, Drawer, Form, Input, Select, Segmented, Space, Table, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useEffect, useMemo, useState } from 'react';
import type { IngestionPlanMappingRuleUpdateRequest } from '../../../types';
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
      message.error('valueMap supports at most 200 entries.');
      return false;
    }
    for (const row of rows) {
      if (!row.sourceValue) {
        message.error('valueMap source value is required.');
        return false;
      }
      if (!row.mappedValue) {
        message.error('valueMap mapped value is required.');
        return false;
      }
      if (row.sourceValue.length > 200) {
        message.error('valueMap source value must be 200 characters or fewer.');
        return false;
      }
      if (row.mappedValue.length > 500) {
        message.error('valueMap mapped value must be 500 characters or fewer.');
        return false;
      }
    }
    if (duplicateKeys.size) {
      message.error('valueMap source values must be unique.');
      return false;
    }
    if (onMissing === 'useDefault' && !missingDefaultValue) {
      message.error('defaultValue is required when onMissing is useDefault.');
      return false;
    }
    if (missingDefaultValue.length > 500) {
      message.error('defaultValue must be 500 characters or fewer.');
      return false;
    }
    return true;
  }

  async function save() {
    if (!mapping) {
      return;
    }
    if (active) {
      message.warning('Active plans cannot be edited directly.');
      return;
    }

    let transformRule: string | null = selectedRule;
    let transformRulePayload: IngestionPlanMappingRuleUpdateRequest['transformRulePayload'] = null;
    if (selectedRule === 'none') {
      transformRule = null;
    } else if (selectedRule === 'defaultIfBlank') {
      if (!defaultValue) {
        message.error('defaultIfBlank value is required.');
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
      title: 'Source value',
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
      title: 'Mapped value',
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
          Remove
        </Button>
      ),
    },
  ];

  return (
    <Drawer
      title="Configure transform rule"
      width={720}
      open={open}
      onClose={onClose}
      destroyOnClose
      extra={(
        <Space>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="primary" loading={saving} disabled={active} onClick={save}>
            Save rule
          </Button>
        </Space>
      )}
    >
      {active && (
        <Alert
          showIcon
          type="warning"
          message="Active plan cannot be edited"
          description="Create a new draft before changing mapping rules."
          style={{ marginBottom: 16 }}
        />
      )}
      <Alert
        showIcon
        type="info"
        message="Rule changes are saved to plan_json.fieldMappingDetails."
        description="Run Shadow Run again to verify the new rule before activation."
        style={{ marginBottom: 16 }}
      />
      <Form layout="vertical">
        <Form.Item label="Mapping">
          <Space size={[8, 8]} wrap>
            <Typography.Text code>{mapping?.sourceField || '-'}</Typography.Text>
            <Typography.Text>to</Typography.Text>
            <Typography.Text code>{mapping?.standardField || '-'}</Typography.Text>
          </Space>
        </Form.Item>
        <Form.Item label="Transform rule">
          <Segmented<RuleKind>
            value={selectedRule}
            onChange={setSelectedRule}
            options={[
              { label: 'None', value: 'none' },
              { label: 'Trim', value: 'trim' },
              { label: 'Lower', value: 'lower' },
              { label: 'Upper', value: 'upper' },
              { label: 'Default', value: 'defaultIfBlank' },
              { label: 'valueMap', value: 'valueMap' },
            ]}
          />
        </Form.Item>

        {selectedRule === 'defaultIfBlank' && (
          <Form.Item label="Default value" required>
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
                message="Empty values map"
                description="All source values will follow onMissing behavior."
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
              Add mapping
            </Button>
            <Form.Item label="onMissing">
              <Select<OnMissing>
                value={onMissing}
                options={[
                  { label: 'keepOriginal', value: 'keepOriginal' },
                  { label: 'useDefault', value: 'useDefault' },
                ]}
                onChange={setOnMissing}
              />
            </Form.Item>
            {onMissing === 'useDefault' && (
              <Form.Item label="defaultValue" required>
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
