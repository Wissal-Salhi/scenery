#version 450 core
#extension GL_ARB_separate_shader_objects: enable

layout(location = 0) in vec3 vertexPosition;
layout(location = 1) in vec3 vertexNormal;
layout(location = 2) in vec2 vertexTexCoord;
layout(location = 3) in mat4 ModelMatrix;
layout(location = 7) in mat4 ModelViewMatrix;
layout(location = 11) in mat4 MVP;

layout(location = 0) out VertexData {
    vec3 Position;
    vec3 Normal;
    vec2 TexCoord;
    vec3 FragPosition;
} VertexOut;

layout(binding = 0) uniform Matrices {
	mat4 ModelViewMatrix;
	mat4 ModelMatrix;
	mat4 ProjectionMatrix;
	mat4 MVP;
	vec3 CamPosition;
	int isBillboard;
} ubo;


void main()
{
/*
	mat4 mv = ModelViewMatrix;
	mat4 nMVP;

	if(ubo.isBillboard > 0) {
		mv[0][0] = 1.0f;
		mv[0][1] = .0f;
		mv[0][2] = .0f;

		mv[1][0] = .0f;
		mv[1][1] = 1.0f;
		mv[1][2] = .0f;

		mv[2][0] = .0f;
		mv[2][1] = .0f;
		mv[2][2] = 1.0f;

		nMVP = ubo.ProjectionMatrix*mv;
	} else {
	    nMVP = ubo.MVP;
	}*/

	VertexOut.Normal = transpose(inverse(mat3(ModelMatrix)))*vertexNormal;
	VertexOut.Position = vec3( ModelViewMatrix * vec4(vertexPosition, 1.0));
	VertexOut.TexCoord = vertexTexCoord;
	VertexOut.FragPosition = vec3(ModelMatrix * vec4(vertexPosition, 1.0));

	gl_Position = MVP * vec4(vertexPosition, 1.0);
}


