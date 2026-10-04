Pod::Spec.new do |spec|
  spec.name = 'GYDiagnosticsNative'
  spec.version = '0.2.0-rc.1'
  spec.summary = '应用私有目录中的 MetricKit 与 NSException 原生采集器'
  spec.homepage = 'https://github.com/gycrosskit/diagnostics'
  spec.license = { :type => 'Apache-2.0', :file => 'LICENSE' }
  spec.author = { 'GY CrossKit' => 'https://github.com/gycrosskit' }
  spec.source = { :git => 'https://github.com/gycrosskit/diagnostics.git', :tag => spec.version.to_s }
  spec.ios.deployment_target = '14.0'
  spec.swift_version = '5.9'
  spec.source_files = 'ios-support/*.swift'
  spec.frameworks = 'Foundation', 'MetricKit'
end
