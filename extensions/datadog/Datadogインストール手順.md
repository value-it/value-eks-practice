# EKSクラスターにDatadogエージェントをインストールする手順

## 前提条件
- AWSマーケットプレイスでDatadogを申込済みであること
  - 以下2つのプランをSubscribeする
    - Datadog Pro (Pay-As-You-Go with 14-day Free Trial)
    - Datadog Pro - Pay-As-You-Go (Container Agent)
- Datadogアカウント開設済み且つ、AWSアカウントを連結済みであること
- 作業用端末にhelmコマンドがインストールされていること
- EKSクラスターが作成済みで作業用端末の操作対象コンテキストになっていること

---

## Datadogエージェントインストール手順
参考
> https://blog.serverworks.co.jp/k8s-datadog

### 1. Datadogにログインし以下のURLからAPIキーを確認する
> https://app.datadoghq.com/organization-settings/api-keys

### 2. 作業用端末からk8sクラスタにエージェントをインストールする 
```shell
# 操作対象コンテキストを念の為確認
kubectl config get-contexts
kubectl config current-context

# Helmチャートファイルをダウンロード
curl -o datadog-charts.yaml https://raw.githubusercontent.com/DataDog/helm-charts/master/charts/datadog/values.yaml
# Helmリポジトリ追加
helm repo add datadog https://helm.datadoghq.com
helm repo update

# 上記手順で取得したAPIキーを変数にセット
DATADOG_API_KEY={上記手順で取得したAPIキー}

# インストール
helm upgrade --install datadog \
-f datadog-charts.yaml \
-n kube-system \
--set datadog.apiKey=$DATADOG_API_KEY \
datadog/datadog

#helm uninstall datadog -n kube-system

# インストールされたことを確認
kubectl get svc -n kube-system
# 以下の3サービスが登録されていること
# datadog
# datadog-cluster-agent
# datadog-cluster-agent-admission-controller
```

### 3. ダッシュボード確認
数分待つとKubernetesダッシュボードに各種Metricsがあがってくる
> https://app.datadoghq.com/dashboard/lists?q=cluster+overview

---

## APMとログ連携を動かす手順

### 1. DatadogエージェントのHelmコンフィグレーションで以下を設定
datadog-charts.yaml の以下の箇所を編集
```yaml
datadog:
  # APM連携を有効にする場合は以下をtrueにする
  apm:
    portEnabled: true
  
  # ログ連携を有効にする場合は以下の2つをtrueにする
  logs:
    enabled: true
    containerCollectAll: true
    
agents:
  containers:
    agent:
      env:
        - name: DD_CLOUD_PROVIDER_METADATA
          value: "aws"
          # デフォルトのままだとGCPのAPIを呼ぼうとしてエラーログを吐くためAWSを明示的に指定
```

反映
```shell
helm upgrade datadog \
-f datadog-charts.yaml \
-n kube-system \
--set datadog.apiKey=$DATADOG_API_KEY \
datadog/datadog
```

### 2. アプリに設定追加
アプリDeployマニフェストファイルに環境変数追加しデプロイ
```yaml
env:
  # Datadog APM用にアプリケーションがトレースを送信する先のホスト指定
  - name: DD_AGENT_HOST
    valueFrom:
      fieldRef:
        fieldPath: status.hostIP
  # Datadog に送信するトレース情報に付与する環境識別子指定
  - name: DD_ENV
    value: sample-env
```
※`DD_ENV` はDatadogエージェント側の設定でも可能だが、ログ連携時にenvが連携されない仕様のためアプリDeployマニフェストで指定する

---

## DatadogでAWS各種リソースを監視するための連携設定
EC2やALB等、EKSクラスター以外のリソースを監視する場合はAmazon Web Services Integrationのセットアップが必要

## セットアップ手順

### Amazon Web Services Integration追加

> https://app.datadoghq.com/integrations/amazon-web-services

1. Limit metric collection by AWS Service でmetric収集対象のサービスを選ぶ（後で変更は可能なので一旦デフォルトのままでも良い）
2. `Update COnfiguration`ボタンを押してmetric収集対象を保存する
3. 連携したいAWSマネコンにログインした状態で`AWS Accounts のAutomatically Using CloudFormation`ボタンを押す
4. CloudFormationのスタック作成に遷移するのでそのままスタック作成
5. 連携に必要なIAMやLamda等各種リソースが出来上がる


