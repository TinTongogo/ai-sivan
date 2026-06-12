/**
 * Forest 树节点类型 — 与后端 TreeNode 接口对应。
 */

export type NodeStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'SKIPPED';

export type Mode = 'SEQUENTIAL' | 'PARALLEL' | 'CONDITIONAL' | 'HIERARCHICAL' | 'CONSENSUS';

export interface TreeNode {
  nodeId: string;
  nodeType: string;
  status: NodeStatus;
  mode?: Mode;
  content?: string;
  metadata?: Record<string, unknown>;
  children: TreeNode[];
}

export interface ForestProgress {
  goalId: string;
  title?: string;
  progress: number;
  completed: number;
  activated: number;
  total: number;
  depth: number;
}

/** Mode → 简短标签 + 符号 */
export const MODE_LABELS: Record<Mode, { symbol: string; label: string }> = {
  SEQUENTIAL: { symbol: '\u2192', label: 'SEQUENTIAL' },
  PARALLEL: { symbol: '\u2194', label: 'PARALLEL' },
  CONDITIONAL: { symbol: '?', label: 'CONDITIONAL' },
  HIERARCHICAL: { symbol: '\u229E', label: 'HIERARCHICAL' },
  CONSENSUS: { symbol: '\u2295', label: 'CONSENSUS' },
};

/** NodeStatus → 显示文本 */
export const STATUS_TEXT: Record<NodeStatus, string> = {
  PENDING: '等待',
  RUNNING: '执行中',
  COMPLETED: '完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  SKIPPED: '跳过',
};
