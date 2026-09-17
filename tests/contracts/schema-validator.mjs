import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const read = (relative) => JSON.parse(readFileSync(new URL(relative, import.meta.url), 'utf8'));
const externalDocuments = {
  './conversation-event.schema.json': read('../../contracts/local-runtime/schemas/conversation-event.schema.json'),
  '../openapi.json': read('../../contracts/local-runtime/openapi.json'),
};
function deepEqual(left, right) {
  try {
    assert.deepStrictEqual(left, right);
    return true;
  } catch {
    return false;
  }
}

function pointerValue(document, pointer) {
  if (pointer === '' || pointer === '#') return document;
  assert(pointer.startsWith('#/'), `Unsupported JSON pointer: ${pointer}`);
  return pointer
    .slice(2)
    .split('/')
    .map((part) => part.replaceAll('~1', '/').replaceAll('~0', '~'))
    .reduce((value, part) => {
      assert(
        value !== null && typeof value === 'object' && part in value,
        `Unresolved JSON pointer ${pointer}`,
      );
      return value[part];
    }, document);
}

function instanceType(value) {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  if (Number.isInteger(value)) return 'integer';
  if (typeof value === 'number') return 'number';
  return typeof value;
}

function matchesType(value, type) {
  if (type === 'number') return typeof value === 'number' && Number.isFinite(value);
  if (type === 'integer') return Number.isInteger(value);
  if (type === 'object') return value !== null && typeof value === 'object' && !Array.isArray(value);
  if (type === 'array') return Array.isArray(value);
  if (type === 'null') return value === null;
  return typeof value === type;
}

function validateFormat(value, format) {
  if (format === 'uuid') {
    return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
  }
  if (format === 'date-time') {
    return (
      /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value) &&
      Number.isFinite(Date.parse(value))
    );
  }
  if (format === 'uri-reference') {
    return value.length > 0 && !/\s/.test(value);
  }
  return true;
}

function validateJsonSchema(instance, schema, rootSchema, path = '$') {
  const errors = [];
  const fail = (message) => errors.push(`${path}: ${message}`);

  if (schema === true || schema === undefined) return errors;
  if (schema === false) {
    fail('schema is false');
    return errors;
  }

  if (schema.$ref) {
    if (schema.$ref.startsWith('#')) {
      return validateJsonSchema(instance, pointerValue(rootSchema, schema.$ref), rootSchema, path);
    }
    const [file, fragment] = schema.$ref.split('#');
    const external = externalDocuments[file];
    assert(external, `Unknown external schema ${file}`);
    return validateJsonSchema(instance, fragment ? pointerValue(external, `#${fragment}`) : external, external, path);
  }

  if (schema.allOf) {
    for (const child of schema.allOf) {
      errors.push(...validateJsonSchema(instance, child, rootSchema, path));
    }
  }

  if (schema.anyOf) {
    const results = schema.anyOf.map((child) =>
      validateJsonSchema(instance, child, rootSchema, path),
    );
    if (!results.some((result) => result.length === 0)) {
      fail('does not satisfy anyOf');
    }
  }

  if (schema.oneOf) {
    const matchCount = schema.oneOf.filter(
      (child) => validateJsonSchema(instance, child, rootSchema, path).length === 0,
    ).length;
    if (matchCount !== 1) {
      fail(`must satisfy exactly one oneOf branch; matched ${matchCount}`);
    }
  }

  if (schema.if) {
    const conditionMatches = validateJsonSchema(instance, schema.if, rootSchema, path).length === 0;
    if (conditionMatches && schema.then) {
      errors.push(...validateJsonSchema(instance, schema.then, rootSchema, path));
    } else if (!conditionMatches && schema.else) {
      errors.push(...validateJsonSchema(instance, schema.else, rootSchema, path));
    }
  }

  if ('const' in schema && !deepEqual(instance, schema.const)) {
    fail(`must equal ${JSON.stringify(schema.const)}`);
  }

  if (schema.enum && !schema.enum.some((candidate) => deepEqual(instance, candidate))) {
    fail(`must be one of ${JSON.stringify(schema.enum)}`);
  }

  if (schema.type) {
    const expected = Array.isArray(schema.type) ? schema.type : [schema.type];
    if (!expected.some((type) => matchesType(instance, type))) {
      fail(`expected ${expected.join('|')}, got ${instanceType(instance)}`);
      return errors;
    }
  }

  if (typeof instance === 'string') {
    if (schema.minLength !== undefined && instance.length < schema.minLength) {
      fail(`length must be >= ${schema.minLength}`);
    }
    if (schema.maxLength !== undefined && instance.length > schema.maxLength) {
      fail(`length must be <= ${schema.maxLength}`);
    }
    if (schema.pattern && !new RegExp(schema.pattern, 'u').test(instance)) {
      fail(`must match ${schema.pattern}`);
    }
    if (schema.format && !validateFormat(instance, schema.format)) {
      fail(`must have format ${schema.format}`);
    }
  }

  if (typeof instance === 'number' && Number.isFinite(instance)) {
    if (schema.minimum !== undefined && instance < schema.minimum) {
      fail(`must be >= ${schema.minimum}`);
    }
    if (schema.maximum !== undefined && instance > schema.maximum) {
      fail(`must be <= ${schema.maximum}`);
    }
  }

  if (Array.isArray(instance)) {
    if (schema.minItems !== undefined && instance.length < schema.minItems) {
      fail(`must contain at least ${schema.minItems} items`);
    }
    if (schema.maxItems !== undefined && instance.length > schema.maxItems) {
      fail(`must contain at most ${schema.maxItems} items`);
    }
    if (schema.uniqueItems) {
      for (let index = 0; index < instance.length; index += 1) {
        if (instance.slice(0, index).some((item) => deepEqual(item, instance[index]))) {
          fail(`item ${index} is duplicated`);
        }
      }
    }

    const prefixLength = schema.prefixItems?.length ?? 0;
    schema.prefixItems?.forEach((itemSchema, index) => {
      if (index < instance.length) {
        errors.push(
          ...validateJsonSchema(instance[index], itemSchema, rootSchema, `${path}[${index}]`),
        );
      }
    });
    if (schema.items !== undefined) {
      for (let index = prefixLength; index < instance.length; index += 1) {
        errors.push(
          ...validateJsonSchema(instance[index], schema.items, rootSchema, `${path}[${index}]`),
        );
      }
    }
  }

  if (instance !== null && typeof instance === 'object' && !Array.isArray(instance)) {
    const propertyCount = Object.keys(instance).length;
    if (schema.minProperties !== undefined && propertyCount < schema.minProperties) {
      fail(`must contain at least ${schema.minProperties} properties`);
    }
    if (schema.maxProperties !== undefined && propertyCount > schema.maxProperties) {
      fail(`must contain at most ${schema.maxProperties} properties`);
    }

    for (const required of schema.required ?? []) {
      if (!(required in instance)) fail(`missing required property ${required}`);
    }

    for (const [trigger, dependencies] of Object.entries(schema.dependentRequired ?? {})) {
      if (!(trigger in instance)) continue;
      for (const dependency of dependencies) {
        if (!(dependency in instance)) {
          fail(`property ${trigger} requires property ${dependency}`);
        }
      }
    }

    const declared = schema.properties ?? {};
    for (const [key, value] of Object.entries(instance)) {
      if (key in declared) {
        errors.push(
          ...validateJsonSchema(value, declared[key], rootSchema, `${path}.${key}`),
        );
      } else if (schema.additionalProperties === false) {
        fail(`unexpected property ${key}`);
      } else if (
        schema.additionalProperties &&
        typeof schema.additionalProperties === 'object'
      ) {
        errors.push(
          ...validateJsonSchema(
            value,
            schema.additionalProperties,
            rootSchema,
            `${path}.${key}`,
          ),
        );
      }
    }
  }

  return errors;
}


export { validateJsonSchema };
