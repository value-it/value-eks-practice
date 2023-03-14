FROM mcr.microsoft.com/dotnet/aspnet:6.0 AS base
WORKDIR /app

FROM mcr.microsoft.com/dotnet/sdk:6.0 AS build
# Download the tracer
WORKDIR /opt
RUN curl -LO https://github.com/DataDog/dd-trace-dotnet/releases/download/v2.17.0/datadog-dotnet-apm_2.17.0_amd64.deb

WORKDIR /src
COPY ["sample-application-csharp.csproj", "SampleApp/"]
RUN dotnet restore "SampleApp/sample-application-csharp.csproj"
WORKDIR "/src/SampleApp"
COPY . .
RUN dotnet build "sample-application-csharp.csproj" -c Release -o /app/build

FROM build AS publish
RUN dotnet publish "sample-application-csharp.csproj" -c Release -o /app/publish

FROM base AS final
WORKDIR /app
COPY --from=publish /app/publish .

# Install the tracer
COPY --from=publish /opt /opt
RUN mkdir -p /opt/datadog
RUN mkdir -p /var/log/datadog
RUN cd /opt && echo `pwd`
RUN mkdir -p /opt/datadog \
    && mkdir -p /var/log/datadog \
    && cd /opt \
    && DEV_FILE=`ls datadog-dotnet-apm_*.deb` \
    && dpkg -i ./${DEV_FILE} \
    && rm ./${DEV_FILE}

# Enable the tracer
ENV CORECLR_ENABLE_PROFILING=1
ENV CORECLR_PROFILER={846F5F1C-F9AE-4B07-969E-05C26BC060D8}
ENV CORECLR_PROFILER_PATH=/opt/datadog/Datadog.Trace.ClrProfiler.Native.so
ENV DD_DOTNET_TRACER_HOME=/opt/datadog
ENV DD_INTEGRATIONS=/opt/datadog/integrations.json

ENV ASPNETCORE_URLS http://*:5000

ENTRYPOINT ["dotnet", "sample-application-csharp.dll"]
