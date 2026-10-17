Pod::Spec.new do |spec|
  spec.name = 'GYDiagnosticsUmeng'
  spec.summary = '友盟崩溃与远程日志适配器'
  spec.version = '0.2.0-rc.13'
  spec.homepage = 'https://github.com/gycrosskit/diagnostics'
  spec.license = { :type => 'Apache-2.0', :file => 'LICENSE' }
  spec.author = { 'GY CrossKit' => 'https://github.com/gycrosskit' }
  spec.source = { :git => 'https://github.com/gycrosskit/diagnostics.git', :tag => spec.version.to_s }
  spec.ios.deployment_target = '15.0'
  spec.swift_version = '5.9'
  spec.frameworks = 'Foundation'
  spec.source_files = 'ios-sdk/Umeng/*.swift'
  spec.dependency 'GYDiagnosticsSdk', spec.version.to_s
  spec.dependency 'UMCommon', '7.6.8'
  spec.dependency 'UMDevice', '3.6.0'
  spec.dependency 'UMAPM', '2.0.7'
end
