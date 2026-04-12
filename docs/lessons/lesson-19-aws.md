# Lesson 19: AWS ECS Deployment

## Part A: ECS Core Concepts

### AWS Container Deployment Architecture

```
Developer → GitHub Actions → ECR (Image Registry) → ECS (Container Orchestration)
                                                          ↓
                                                    ALB (Load Balancer)
                                                      ↙        ↘
                                                  Task 1      Task 2
                                                (Container)  (Container)
```

### ECS 核心概念

| 概念 | 類比 | 說明 |
|---|---|---|
| **Cluster** | 機房 | 邏輯分組，管理所有 Service |
| **Service** | 部門 | 維護指定數量嘅 Task，自動替換掛咗嘅 |
| **Task Definition** | 說明書 | 定義 container 點跑（image, CPU, memory, env vars） |
| **Task** | 員工 | Task Definition 嘅實際運行實例（= 1 個 container） |
| **Fargate** | 外判 | Serverless，唔使管 EC2 instance，AWS 幫你搞 |

### ECR (Elastic Container Registry)

- AWS 嘅 **private Docker Hub**
- `docker push` image 去 ECR，ECS 從 ECR pull image
- Image URL 格式：`{account_id}.dkr.ecr.{region}.amazonaws.com/{repo}:{tag}`

### ALB (Application Load Balancer)

- 分配 user traffic 去多個 Task（round-robin 或 least connections）
- User 只知道 ALB 嘅 URL，唔知道背後有幾多個 Task
- Health check 自動踢走 unhealthy Task

---

## Part B: Task Definition

### task-definition.json

```json
{
  "family": "online-shopping",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512",
  "memory": "1024",
  "containerDefinitions": [{
    "name": "OnlineShopping",
    "image": "123456789.dkr.ecr.us-east-1.amazonaws.com/online-shopping:latest",
    "portMappings": [{"containerPort": 8080, "protocol": "tcp"}],
    "environment": [...],
    "secrets": [...],
    "logConfiguration": {...}
  }]
}
```

**Key fields:**

| Field | 說明 |
|---|---|
| `family` | Task Definition 嘅名（同一 family 可以有多個 revision） |
| `networkMode: awsvpc` | 每個 Task 有自己嘅 ENI（網路介面），Fargate 必須用 |
| `cpu / memory` | Fargate 嘅資源規格（512 CPU = 0.5 vCPU） |
| `containerDefinitions` | 定義 container 嘅 image、port、env vars |

### Environment vs Secrets

```json
"environment": [
  { "name": "SPRING_DATASOURCE_URL", "value": "jdbc:mysql://rds-endpoint:3306/online_shopping" },
  { "name": "SPRING_DATASOURCE_USERNAME", "value": "root" }
],
"secrets": [
  { "name": "SPRING_DATASOURCE_PASSWORD",
    "valueFrom": "arn:aws:secretsmanager:us-east-1:123456789:secret:db-password" }
]
```

- **environment** — 非敏感 config（DB URL, Redis host）
- **secrets** — 敏感資料（password, API key），從 AWS Secrets Manager 拎
- **點解唔全部放 environment？** — env vars 會出現喺 Task Definition JSON、CloudWatch logs、`docker inspect`，password 放入去等於明文暴露

### Log Configuration

```json
"logConfiguration": {
  "logDriver": "awslogs",
  "options": {
    "awslogs-group": "/ecs/online-shopping",
    "awslogs-region": "us-east-1",
    "awslogs-stream-prefix": "ecs"
  }
}
```

- Container 嘅 stdout/stderr 自動送去 **CloudWatch Logs**
- 等於 `docker logs` 但係 centralized，唔使 SSH 入去睇

---

## Part C: Network Architecture

### 點解 RDS/ElastiCache 放 Private Subnet？

```
Internet
   ↓
  ALB (Public Subnet)
   ↓
  ECS Tasks (Private Subnet)
   ↓
  RDS / ElastiCache / RocketMQ (Private Subnet)
```

- **Security** — Database 唔應該有 public IP，只有 ECS Task 可以 access
- **只有 App 需要 call DB** — 外部 code 唔需要直接連 DB
- **如果真係要遠程 access** — 用 VPN 或 SSH tunnel，唔好開 public access

### Security Group 概念

- RDS Security Group 只 allow ECS Task 嘅 Security Group inbound
- ECS Task Security Group 只 allow ALB 嘅 Security Group inbound
- ALB Security Group allow 0.0.0.0/0:443 (HTTPS from internet)

---

## Part D: GitHub Actions Deploy Workflow

### deploy.yml

```yaml
name: Deploy to ECS
on:
  workflow_dispatch:  # manual trigger（冇 AWS credentials 唔會自動跑）

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: us-east-1

      - name: Login to ECR
        id: login-ecr
        uses: aws-actions/amazon-ecr-login@v2

      - name: Build and push image
        env:
          ECR_REGISTRY: ${{ steps.login-ecr.outputs.registry }}
          IMAGE_TAG: ${{ github.sha }}
        run: |
          docker build -t $ECR_REGISTRY/online-shopping:$IMAGE_TAG .
          docker push $ECR_REGISTRY/online-shopping:$IMAGE_TAG

      - name: Deploy to ECS
        uses: aws-actions/amazon-ecs-deploy-task-definition@v2
        with:
          task-definition: ecs/task-definition.json
          service: online-shopping-service
          cluster: online-shopping-cluster
          wait-for-service-stability: true
```

### Deploy Flow

```
1. Configure AWS Credentials → 用 GitHub Secrets 嘅 IAM key
2. Login to ECR → 攞 Docker login token
3. Build & Push → docker build + tag with git SHA + push 去 ECR
4. Deploy to ECS → 更新 Task Definition → ECS rolling update
```

**Key points:**
- `workflow_dispatch` — 手動觸發，避免冇 AWS credentials 時自動 fail
- `${{ github.sha }}` — 用 git commit hash 做 image tag，方便追蹤邊個 commit 部署咗
- `wait-for-service-stability` — 等 ECS 確認新 Task healthy 先完成 workflow
- **Rolling Update** — ECS 先起新 Task，確認 healthy 後先停舊 Task，zero downtime

### CI vs CD

| | CI (ci.yml) | CD (deploy.yml) |
|---|---|---|
| 觸發 | push/PR to main | 手動 (workflow_dispatch) |
| 做咩 | `mvn test` | Build image + Deploy |
| 失敗影響 | PR 唔畀 merge | 部署失敗，舊版本繼續跑 |

---

## Interview Drills

<details>
<summary>Q1: ECS Fargate 同 EC2 launch type 有咩分別？</summary>

Fargate 係 serverless — 你只需要定義 CPU/memory，AWS 自動管理底層 EC2 instance。EC2 launch type 你自己管 EC2 cluster，有更多控制權但更複雜。Fargate 適合大部分 workload（簡單、auto-scaling 容易），EC2 適合需要 GPU、特殊 instance type、或者想慳錢（Reserved Instance）嘅場景。
</details>

<details>
<summary>Q2: 點解用 Secrets Manager 而唔係直接放 environment variable？</summary>

Environment variable 會出現喺 Task Definition JSON（version controlled）、CloudWatch logs、`docker inspect` output。即係話任何有 ECS read access 嘅人都睇到 password。Secrets Manager 將 secret 加密存儲，runtime 先 inject 入 container，而且支持 rotation、audit log、fine-grained IAM access control。
</details>

<details>
<summary>Q3: ECS rolling update 點運作？點解可以 zero downtime？</summary>

ECS 先啟動新 Task（用新 image），等 ALB health check pass 後將 traffic 導去新 Task，然後停止舊 Task。過程中至少有一個 healthy Task 在運行，所以 user 唔會感受到 downtime。`wait-for-service-stability` 確保 CI/CD pipeline 等到整個 rolling update 完成先 report success/failure。
</details>

<details>
<summary>Q4: 點解 RDS 放 Private Subnet？點樣確保只有 ECS Task 可以 access？</summary>

RDS 放 Private Subnet 冇 public IP，internet 直接 access 唔到。用 Security Group 限制：RDS 嘅 Security Group 只 allow ECS Task 嘅 Security Group 作為 inbound source。即使有人攞到 DB connection string，冇喺同一個 VPC 入面都連唔到。如果開發人員需要遠程 access，用 VPN 或 SSH tunnel 通過 bastion host。
</details>

<details>
<summary>Q5: 點解用 git SHA 做 image tag 而唔係 latest？</summary>

`latest` tag 唔知道跑緊邊個 version — rollback 時唔知道應該去邊個 image。Git SHA 做 tag 可以 1:1 追蹤每個 deployment 對應邊個 commit，rollback 時直接指定之前嘅 SHA tag。而且 `latest` 有 cache 問題 — Docker/ECS 可能唔 pull 最新版本因為 tag 冇變。
</details>
