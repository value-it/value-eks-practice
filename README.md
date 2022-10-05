# EKSクラスターを作成してサンプルJavaアプリケーションをデプロイする雛形

- IngressはAWS Load Balancer Controller（パブリックサブネットを使用）
- サンプルJavaアプリケーションはHelloWorldを画面表示するだけのSpringBootのWEBアプリ
- Datadogエージェントインストール（オプション）

---

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
echo "AWS_ACCOUNT_ID="`aws sts get-caller-identity --query 'Account' --output text`

export K8S_CLUSTER_NAME=eks-practice-cluster
export ECR_APP_REPO_NAME=eks-practice/application
export NODES_SSH_PUBLIC_KEY=eks-practice-key
export REGION=ap-northeast-1
```

## CloudFormationで基本リソース作成
```shell
# ネットワーク(VPC/Subnet等)
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
echo $RES
```

### ノードグループ作成
この処理には10分程度かかる
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
# 失敗した場合は削除して再度実行する
# 削除コマンド
# eksctl delete nodegroup --region=ap-northeast-1 --cluster=$K8S_CLUSTER_NAME --name=$K8S_CLUSTER_NAME-ng1

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

---
## サンプルJavaアプリデプロイ

### ビルド
```shell
aws ecr get-login-password --region $REGION --profile $AWS_PROFILE | docker login --username AWS --password-stdin AWS_ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com
docker build -t hello-world-java .
docker tag hello-world-java:latest ${AWS_ACCOUNT_ID}.dkr.ecr.$REGION.amazonaws.com/${ECR_APP_REPO_NAME}:latest
docker push ${AWS_ACCOUNT_ID}.dkr.ecr.$REGION.amazonaws.com/${ECR_APP_REPO_NAME}:latest
```

### デプロイ
```shell
kubectl create namespace kube-system
cat ./establish/k8s-manifests/deployment.yaml | sed "s|@ecr_container_url|${AWS_ACCOUNT_ID}.dkr.ecr.$REGION.amazonaws.com/${ECR_APP_REPO_NAME}:latest|" | kubectl apply -f -

# 確認
kubectl get pods -n kube-system
```

---
## Ingressインストール
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
--attach-policy-arn=arn:aws:iam::${AWS_ACCOUNT_ID}:policy/AWSLoadBalancerControllerIAMPolicy \
--override-existing-serviceaccounts \
--approve

# TargetGroupBinding のカスタムリソース定義をインストール
kubectl apply -k "github.com/aws/eks-charts/stable/aws-load-balancer-controller/crds?ref=master"

# eks-charts リポジトリを追加
helm repo add eks https://aws.github.io/eks-charts

# ローカルリポジトリを更新して最新のグラフがあることを確認
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

# Ingressをデプロイ
kubectl apply -f ./establish/k8s-manifests/ingress.yaml
```



---

# 削除手順

```shell
# Ingress削除
kubectl delete -f ./establish/k8s-manifests/ingress.yaml

# Ingressコントローラ削除
helm uninstall aws-load-balancer-controller -n kube-system

# Ingress用サービスアカウント削除
eksctl delete iamserviceaccount --cluster $K8S_CLUSTER_NAME --name=aws-load-balancer-controller

# ノードグループ削除
eksctl delete nodegroup --cluster $K8S_CLUSTER_NAME --name $K8S_CLUSTER_NAME-ng1 

# クラスタ削除
eksctl delete cluster --name $K8S_CLUSTER_NAME

# ECRリポジトリ削除
aws ecr delete-repository --repository-name $ECR_APP_REPO_NAME --force
aws cloudformation delete-stack --stack-name eks-practice-template-ecr

# ネットワーク(VPC/Subnet等)削除
aws cloudformation delete-stack --stack-name eks-practice-template-network
aws cloudformation wait stack-delete-complete --stack-name eks-practice-template-network
```

