#version 330 core
layout(location = 0) in mat4 Instance;
layout(location = 4) in vec4 Bounds;
layout(location = 5) in vec3 Data;
layout(std140) uniform Projection { mat4 ProjMat; };
uniform mat4 View;
out float vertexDistance;
out float horizontalDistance;
out float alpha;
out vec2 texCoord;
void main() {
    float x = gl_VertexID >= 2 ? Bounds.z : Bounds.x;
    float z = gl_VertexID == 1 || gl_VertexID == 2 ? Bounds.w : Bounds.y;
    vec3 position = (Instance * vec4(x, Data.x, z, 1.0)).xyz;
    gl_Position = ProjMat * View * vec4(position, 1.0);
    vertexDistance = length(position);
    horizontalDistance = max(length(position.xz), abs(position.y));
    alpha = Data.z;
    texCoord = -vec2(x, z) / (2.0 * Data.y) + 0.5;
}
