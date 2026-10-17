import { DiagnosticSdk } from '@gycrosskit/diagnostics-core';
import { UmengDiagnosticSdk } from './src/main/ets/UmengDiagnosticSdk';

export function createDiagnosticSdk(): DiagnosticSdk { return new UmengDiagnosticSdk(); }
