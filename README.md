# EKSクラスターを作成してサンプルJavaアプリケーションをデプロイする雛形

- IngressはAWS Load Balancer Controller（パブリックサブネットを使用）
- 
- 


# 前提条件

#### 作業用端末にAWS CLI v2がインストールされている
> https://aws.amazon.com/jp/cli/

#### 作業用端末にAWS CLI の名前付きプロファイルが作成されている
> 各種リソースを操作可能なIAMユーザーのプロファイルを作成する  
> https://docs.aws.amazon.com/ja_jp/cli/latest/userguide/cli-configure-profiles.html

#### 作業用端末にeksctlコマンドがインストールされている

#### 作業用端末にkubectlコマンドがインストールされている

#### 作業用端末にhelmコマンドがインストールされている

#### ノードグループEC2用のキーペア「eks-practice-key」が作成されている

---

# EKSクラスター構築手順

## 環境変数設定
適宜環境にあわせて指定する
```shell
# 事前に作成したAWSプロファイルを指定（プロファイル名は適宜修正）
export AWS_PROFILE=hogehoge

# リソース監視にDatadogを使用する場合はAPIキーを指定
# 参考
# https://dev.classmethod.jp/articles/datadog-aws-marketplace/
export DATADOG_API_KEY=xxxxxxxxxxxxxx

export K8S_CLUSTER_NAME=eks-practice-cluster
export ECR_APP_REPO_NAME=eks-practice/application
export NODES_SSH_PUBLIC_KEY=eks-practice-key
export REGION=ap-northeast-1
```

## CloudFormationで基本リソース作成
```shell
# ネットワーク
aws cloudformation deploy \
--stack-name eks-practice-template-network \
--template-file ./establish/cloudformation/01-create-network.yaml \
--parameter-overrides K8sClusterName=$K8S_CLUSTER_NAME

# ECRリポジトリ
aws cloudformation deploy \
--stack-name eks-practice-template-ecr \
--template-file ./establish/cloudformation/02-ecr.yaml \
--parameter-overrides RepositoryName=$ECR_APP_REPO_NAME
```

## EKSでk8sクラスター構築

### Cluster作成
この処理には10分〜20分程度かかる
```shell
# 前手順で作成したSubnetからタグ「ForK8SPrivateCluster=true」がついているSubnetのIDリストをカンマ区切りで取得
K8S_CLUSTER_SUBNETS=$(aws ec2 describe-subnets --filters "Name=tag:ForK8SPrivateCluster,Values=true" --query 'Subnets[].SubnetId | join(`","`, @)' --output=text)
echo "subnets: $K8S_CLUSTER_SUBNETS"

eksctl create cluster \
--name $K8S_CLUSTER_NAME \
--region $REGION \
--vpc-private-subnets $K8S_CLUSTER_SUBNETS \
--with-oidc \
--without-nodegroup
```

### CloudWatch Logs有効化
```shell
RES=`aws eks update-cluster-config \
--region $REGION \
--name $K8S_CLUSTER_NAME \
--logging '{"clusterLogging":[{"types":["api","audit","authenticator","controllerManager","scheduler"],"enabled":true}]}'`
```

### ノードグループ作成
この処理には5分程度かかる
```shell
eksctl create nodegroup \
--cluster $K8S_CLUSTER_NAME \
--name $K8S_CLUSTER_NAME-ng1 \
--node-private-networking \
--node-type t3.small \
--node-volume-size 10 \
--node-volume-type gp2 \
--nodes 2 \
--nodes-min 2 \
--nodes-max 2 \
--ssh-access \
--ssh-public-key $NODES_SSH_PUBLIC_KEY \
--managed
# 一度失敗しても二回目やるとうまくいく？？
# 削除コマンド
# eksctl delete nodegroup --region=ap-northeast-1 --cluster=bootapps-k8s --name=bootapps-k8s-ng1

# 作成したノードグループ一覧確認
eksctl get nodegroup --cluster $K8S_CLUSTER_NAME
```

---

## 各種DaemonSetsのセットアップ

## CloudWatchLogs（Fluentbit）
CloudWatchLogsへログを送信するDaemonSetとしてFluentBitを設定
```shell
# ノードグループのロールARN取得
NODE_GROUP_ARN=`eksctl get iamidentitymapping --region ap-northeast-1 --cluster $K8S_CLUSTER_NAME | awk '2==NR{print $1}'`
echo $NODE_GROUP_ARN

# ポリシーのアタッチ
aws iam attach-role-policy \
--role-name ${NODE_GROUP_ARN##*/} \
--policy-arn "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"

# CloudWatch名前空間作成
kubectl apply -f https://raw.githubusercontent.com/aws-samples/amazon-cloudwatch-container-insights/latest/k8s-deployment-manifest-templates/deployment-mode/daemonset/container-insights-monitoring/cloudwatch-namespace.yaml

# ConfigMap作成
FluentBitHttpPort='2020'
FluentBitReadFromHead='Off'
[[ ${FluentBitReadFromHead} = 'On' ]] && FluentBitReadFromTail='Off'|| FluentBitReadFromTail='On'
[[ -z ${FluentBitHttpPort} ]] && FluentBitHttpServer='Off' || FluentBitHttpServer='On'
kubectl create configmap fluent-bit-cluster-info \
--from-literal=cluster.name=${K8S_CLUSTER_NAME} \
--from-literal=http.server=${FluentBitHttpServer} \
--from-literal=http.port=${FluentBitHttpPort} \
--from-literal=read.head=${FluentBitReadFromHead} \
--from-literal=read.tail=${FluentBitReadFromTail} \
--from-literal=logs.region=${REGION} -n amazon-cloudwatch

# FluentBit daemonsetをクラスターにダウンロードしてデプロイ
kubectl apply -f https://raw.githubusercontent.com/aws-samples/amazon-cloudwatch-container-insights/latest/k8s-deployment-manifest-templates/deployment-mode/daemonset/container-insights-monitoring/fluent-bit/fluent-bit-compatible.yaml

# デプロイされたPodを確認（2件表示される）
kubectl get pods -n amazon-cloudwatch
```

## Datadogエージェント
Datadogを使用しない場合はこの手順スキップ
```shell
# Helmチャートファイルをダウンロード
curl -O https://raw.githubusercontent.com/DataDog/helm-charts/master/charts/datadog/values.yaml
# Helmリポジトリ追加
helm repo add datadog https://helm.datadoghq.com
# インストール
helm install eks-practice-datadog -f values.yaml --set datadog.apiKey=$DATADOG_API_KEY datadog/datadog
```

---
## サンプルJavaアプリデプロイ

### ビルド
```shell
ACCOUNT_ID=`aws sts get-caller-identity --query 'Account' --output text`
aws ecr get-login-password --region $REGION --profile $AWS_PROFILE | docker login --username AWS --password-stdin $ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com
docker build -t hello-world-java .
docker tag hello-world-java:latest ${ACCOUNT_ID}.dkr.ecr.$REGION.amazonaws.com/${ECR_APP_REPO_NAME}:latest
docker push ${ACCOUNT_ID}.dkr.ecr.$REGION.amazonaws.com/${ECR_APP_REPO_NAME}:latest
```

### デプロイ
```shell
kubectl create namespace kube-system
cat ./establish/k8s-manifests/deployment.yaml | sed "s|@ecr_container_url|${ACCOUNT_ID}.dkr.ecr.$REGION.amazonaws.com/${ECR_APP_REPO_NAME}:latest|" | kubectl apply -f -

# 確認
kubectl get pods
```

### Ingress
```shell
# ユーザーに代わって AWS API を呼び出すことを許可する、AWS Load Balancer Controller 用の IAM ポリシーをダウンロード
curl -o iam_policy.json https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/v2.4.0/docs/install/iam_policy.json

# ポリシーの作成
aws iam create-policy \
--policy-name AWSLoadBalancerControllerIAMPolicy \
--policy-document file://iam_policy.json

# サービスアカウント用 & IAM ロール作成
eksctl create iamserviceaccount \
--cluster=${K8S_CLUSTER_NAME} \
--namespace=kube-system \
--name=aws-load-balancer-controller \
--attach-policy-arn=arn:aws:iam::${ACCOUNT_ID}:policy/AWSLoadBalancerControllerIAMPolicy \
--override-existing-serviceaccounts \
--approve

# TargetGroupBinding のカスタムリソース定義をインストール
kubectl apply -k "github.com/aws/eks-charts/stable/aws-load-balancer-controller/crds?ref=master"

# eks-charts リポジトリを追加します。
helm repo add eks https://aws.github.io/eks-charts

# ローカルリポジトリを更新して、最新のグラフがあることを確認します。
helm repo update

# コントローラをインストール
helm upgrade -i aws-load-balancer-controller eks/aws-load-balancer-controller \
--set clusterName=${K8S_CLUSTER_NAME} \
--set serviceAccount.create=false \
--set serviceAccount.name=aws-load-balancer-controller \
-n kube-system

# コントローラがインストールされている事を確認（aws-load-balancer-controller が表示される）
helm ls -n kube-system

# ingress - controller ログ確認
kubectl logs -n kube-system deployment.apps/aws-load-balancer-controller

# ingressをデプロイ
kubectl apply -f ./establish/k8s-manifests/ingress.yaml
```