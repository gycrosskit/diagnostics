Pod::Spec.new do |spec|
  spec.name = 'GYDiagnosticsBugly'
  spec.summary = 'Bugly 崩溃与远程日志适配器'
  spec.module_name = 'GYDiagnosticsProvider'
  spec.version = '1.0.1'
  spec.homepage = 'https://github.com/gycrosskit/diagnostics'
  spec.license = { :type => 'Apache-2.0', :file => 'LICENSE' }
  spec.author = { 'GY CrossKit' => 'https://github.com/gycrosskit' }
  spec.source = { :git => 'https://github.com/gycrosskit/diagnostics.git', :tag => spec.version.to_s }
  spec.ios.deployment_target = '15.0'
  spec.swift_version = '5.9'
  spec.frameworks = 'Foundation'
  spec.dependency 'GYDiagnosticsSdk', spec.version.to_s
  spec.source_files = 'ios-sdk/Bugly/**/*.swift'
  spec.dependency 'BuglyPro/CrashMonitor', '2.8.2.14'
  # 不使用 DefaultStorage；宿主 KMP 可已静态内嵌 MMKV。
  spec.dependency 'TDOS_Diagnose/Core', '0.8.9.6'
  spec.dependency 'TDOS_Diagnose/DefaultLogger', '0.8.9.6'
  spec.dependency 'TDOS_Diagnose/DefaultFilePacker', '0.8.9.6'
end
