# Use Amazon Corretto 21 as the base image
FROM amazoncorretto:21

# Install build tools, dependencies for FFmpeg, debugging tools, and OpenSSL
RUN yum update -y && \
    yum groupinstall -y "Development Tools" && \
    yum install -y wget tar gzip bzip2-devel nasm pkg-config cmake3 unzip \
                   fribidi-devel fontconfig-devel freetype-devel \
                   iputils bind-utils mysql \
                   openssl openssl-devel && \
    ln -sf /usr/bin/cmake3 /usr/bin/cmake && \
    yum clean all

# Create directory for source files
RUN mkdir -p /tmp/ffmpeg_sources

# Install LAME (MP3 encoder) from source
RUN cd /tmp/ffmpeg_sources && \
    wget https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz && \
    tar -xzf lame-3.100.tar.gz && \
    cd lame-3.100 && \
    ./configure --prefix=/usr/local --enable-shared --enable-nasm && \
    make -j$(nproc) && \
    make install && \
    ldconfig

# Install Opus from source
RUN cd /tmp/ffmpeg_sources && \
    wget https://github.com/xiph/opus/releases/download/v1.4/opus-1.4.tar.gz && \
    tar -xzf opus-1.4.tar.gz && \
    cd opus-1.4 && \
    ./configure --prefix=/usr/local --enable-shared && \
    make -j$(nproc) && \
    make install && \
    ldconfig

# Build harfbuzz from source
RUN cd /tmp/ffmpeg_sources && \
    wget https://github.com/harfbuzz/harfbuzz/releases/download/8.5.0/harfbuzz-8.5.0.tar.xz && \
    tar -xJf harfbuzz-8.5.0.tar.xz && \
    cd harfbuzz-8.5.0 && \
    ./configure --prefix=/usr/local && \
    make -j$(nproc) && \
    make install && \
    ldconfig

# Build libass from source
RUN cd /tmp/ffmpeg_sources && \
    wget https://github.com/libass/libass/releases/download/0.17.3/libass-0.17.3.tar.gz && \
    tar -xzf libass-0.17.3.tar.gz && \
    cd libass-0.17.3 && \
    PKG_CONFIG_PATH="/usr/local/lib/pkgconfig:/usr/lib64/pkgconfig:/usr/lib/pkgconfig" ./configure --prefix=/usr/local && \
    make -j$(nproc) && \
    make install && \
    ldconfig

# Build x264 from source
RUN cd /tmp/ffmpeg_sources && \
    wget https://code.videolan.org/videolan/x264/-/archive/master/x264-master.tar.bz2 && \
    tar xjvf x264-master.tar.bz2 && \
    cd x264-master && \
    ./configure --enable-shared --enable-pic --prefix=/usr/local && \
    make -j$(nproc) && \
    make install && \
    ldconfig

# Build x265 from source
RUN cd /tmp/ffmpeg_sources && \
    wget https://github.com/videolan/x265/archive/master.zip && \
    unzip master.zip && \
    cd x265-master/build/linux && \
    cmake -G "Unix Makefiles" -DCMAKE_INSTALL_PREFIX="/usr/local" -DENABLE_SHARED=ON ../../source && \
    make -j$(nproc) && \
    make install && \
    ldconfig

# Install pkg-config development files
RUN yum install -y pkgconfig

# Build FFmpeg with x264, x265, additional codecs, and HTTPS support
RUN cd /tmp/ffmpeg_sources && \
    wget https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.gz && \
    tar -xzf ffmpeg-7.1.1.tar.gz && \
    cd ffmpeg-7.1.1 && \
    export PKG_CONFIG_PATH="/usr/local/lib/pkgconfig:/usr/lib64/pkgconfig:/usr/lib/pkgconfig" && \
    ./configure \
        --prefix=/usr/local \
        --enable-gpl \
        --enable-nonfree \
        --enable-libx264 \
        --enable-libx265 \
        --enable-libass \
        --enable-libfreetype \
        --enable-libmp3lame \
        --enable-libopus \
        --enable-openssl \
        --extra-ldflags="-L/usr/local/lib -L/usr/lib64" \
        --extra-cflags="-I/usr/local/include -I/usr/include" && \
    make -j$(nproc) && \
    make install && \
    ldconfig

# Update library cache and set LD_LIBRARY_PATH
RUN echo "/usr/local/lib" > /etc/ld.so.conf.d/local-libs.conf && \
    ldconfig

# Clean up
RUN rm -rf /tmp/ffmpeg_sources

# Set environment variables
ENV LD_LIBRARY_PATH=/usr/local/lib:/usr/lib:/lib
ENV FFMPEG_PATH=/usr/local/bin/ffmpeg
ENV FFPROBE_PATH=/usr/local/bin/ffprobe
ENV JAVA_OPTS="-Xms512m -Xmx1024m"
ENV SPRING_PROFILES_ACTIVE=prod

# Set working directory
WORKDIR /app

# Copy the .env file
COPY .env /app/.env

# Copy the Spring Boot JAR
COPY target/Scenith-0.0.1-SNAPSHOT.jar app.jar

# Expose port 1000 (align with application-prod.properties)
EXPOSE 1000

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]