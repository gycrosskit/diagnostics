import { DiagnosticSdk } from '@gycrosskit/diagnostics-core';
import { BuglyDiagnosticSdk } from './src/main/ets/BuglyDiagnosticSdk';

export function createDiagnosticSdk(): DiagnosticSdk { return new BuglyDiagnosticSdk(); }
