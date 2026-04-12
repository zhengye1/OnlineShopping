# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
LABEL authors="Vincent Z"
COPY . .
RUN mvn package -DskipTests

# Stage 2: Run
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar OnlineShopping.jar
EXPOSE 8080
CMD ["java", "-jar", "OnlineShopping.jar"]

