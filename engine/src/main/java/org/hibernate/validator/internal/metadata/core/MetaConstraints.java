/*
 * Hibernate Validator, declare and validate application constraints
 *
 * License: Apache License, Version 2.0
 * See the license.txt file in the root directory or <http://www.apache.org/licenses/LICENSE-2.0>.
 */
package org.hibernate.validator.internal.metadata.core;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.validation.metadata.ValidateUnwrappedValue;

import org.hibernate.validator.internal.engine.valueextraction.ValueExtractorDescriptor;
import org.hibernate.validator.internal.engine.valueextraction.ValueExtractorHelper;
import org.hibernate.validator.internal.engine.valueextraction.ValueExtractorManager;
import org.hibernate.validator.internal.metadata.core.MetaConstraint.TypeParameterAndExtractor;
import org.hibernate.validator.internal.metadata.descriptor.ConstraintDescriptorImpl;
import org.hibernate.validator.internal.metadata.location.ConstraintLocation;
import org.hibernate.validator.internal.metadata.location.TypeArgumentConstraintLocation;
import org.hibernate.validator.internal.util.TypeHelper;
import org.hibernate.validator.internal.util.TypeResolutionHelper;
import org.hibernate.validator.internal.util.TypeVariables;
import org.hibernate.validator.internal.util.logging.Log;
import org.hibernate.validator.internal.util.logging.LoggerFactory;

import com.fasterxml.classmate.ResolvedType;

/**
 * Helper used to create {@link MetaConstraint}s.
 *
 * @author Guillaume Smet
 */
public class MetaConstraints {

	private static final Log LOG = LoggerFactory.make();

	private MetaConstraints() {
	}

	public static <A extends Annotation> MetaConstraint<A> create(TypeResolutionHelper typeResolutionHelper, ValueExtractorManager valueExtractorManager,
			ConstraintDescriptorImpl<A> constraintDescriptor, ConstraintLocation location) {
		List<TypeParameterAndExtractor> valueExtractionPath = new ArrayList<>();

		Type typeOfValidatedElement = addValueExtractorDescriptorForWrappedValue( typeResolutionHelper, valueExtractorManager, constraintDescriptor,
				valueExtractionPath, location );

		ConstraintLocation current = location;
		do {
			if ( current instanceof TypeArgumentConstraintLocation ) {
				addValueExtractorDescriptorForTypeArgumentLocation( valueExtractorManager, valueExtractionPath, (TypeArgumentConstraintLocation) current );
				current = ( (TypeArgumentConstraintLocation) current ).getDelegate();
			}
			else {
				current = null;
			}
		}
		while ( current != null );

		Collections.reverse( valueExtractionPath );

		return new MetaConstraint<>( constraintDescriptor, location, valueExtractionPath, typeOfValidatedElement );
	}

	private static <A extends Annotation> Type addValueExtractorDescriptorForWrappedValue(TypeResolutionHelper typeResolutionHelper,
			ValueExtractorManager valueExtractorManager, ConstraintDescriptorImpl<A> constraintDescriptor,
			List<TypeParameterAndExtractor> valueExtractionPath, ConstraintLocation location) {
		if ( ValidateUnwrappedValue.NO.equals( constraintDescriptor.validateUnwrappedValue() ) ) {
			return location.getTypeForValidatorResolution();
		}

		Class<?> declaredType = TypeHelper.getErasedReferenceType( location.getTypeForValidatorResolution() );
		Set<ValueExtractorDescriptor> valueExtractorDescriptorCandidates = valueExtractorManager.getMaximallySpecificValueExtractors( declaredType );
		ValueExtractorDescriptor selectedValueExtractorDescriptor;

		// we want to force the unwrapping so we require one and only one maximally specific value extractors
		if ( ValidateUnwrappedValue.YES.equals( constraintDescriptor.validateUnwrappedValue() ) ) {
			switch ( valueExtractorDescriptorCandidates.size() ) {
				case 0:
					throw LOG.getNoValueExtractorFoundForTypeException( declaredType, null );
				case 1:
					selectedValueExtractorDescriptor = valueExtractorDescriptorCandidates.iterator().next();
					break;
				default:
					throw LOG.unableToGetMostSpecificValueExtractorDueToSeveralMaximallySpecificValueExtractorsDeclared( declaredType,
							ValueExtractorHelper.toValueExtractorClasses( valueExtractorDescriptorCandidates ) );
			}
		}
		// we are in the implicit (DEFAULT) case so:
		// - if we don't have a maximally specific value extractor marked with @UnwrapByDefault, we don't unwrap
		// - if we have one maximally specific value extractors that is marked with @UnwrapByDefault, we unwrap
		// - otherwise, we throw an exception as we can't choose between the value extractors
		else {
			Set<ValueExtractorDescriptor> unwrapByDefaultValueExtractorDescriptorCandidates = valueExtractorDescriptorCandidates.stream()
					.filter( ved -> ved.isUnwrapByDefault() )
					.collect( Collectors.toSet() );

			switch ( unwrapByDefaultValueExtractorDescriptorCandidates.size() ) {
				case 0:
					return location.getTypeForValidatorResolution();
				case 1:
					selectedValueExtractorDescriptor = unwrapByDefaultValueExtractorDescriptorCandidates.iterator().next();
					break;
				default:
					throw LOG.implicitUnwrappingNotAllowedWhenSeveralMaximallySpecificValueExtractorsMarkedWithUnwrapByDefaultDeclared( declaredType,
							ValueExtractorHelper.toValueExtractorClasses( unwrapByDefaultValueExtractorDescriptorCandidates ) );
			}
		}

		valueExtractionPath.add( TypeParameterAndExtractor.of( selectedValueExtractorDescriptor ) );

		return selectedValueExtractorDescriptor.getExtractedType()
				.orElseGet( () -> getSingleTypeParameterBind( typeResolutionHelper,
						location.getTypeForValidatorResolution(),
						selectedValueExtractorDescriptor ) );
	}

	private static void addValueExtractorDescriptorForTypeArgumentLocation( ValueExtractorManager valueExtractorManager,
			List<TypeParameterAndExtractor> valueExtractionPath, TypeArgumentConstraintLocation typeArgumentConstraintLocation ) {
		Class<?> declaredType = typeArgumentConstraintLocation.getContainerClass();
		TypeVariable<?> typeParameter = typeArgumentConstraintLocation.getTypeParameter();

		ValueExtractorDescriptor valueExtractorDescriptor = valueExtractorManager
				.getMaximallySpecificAndContainerElementCompliantValueExtractor( declaredType, typeParameter );

		if ( valueExtractorDescriptor == null ) {
			throw LOG.getNoValueExtractorFoundForTypeException( declaredType, typeParameter );
		}

		valueExtractionPath.add( TypeParameterAndExtractor.of( typeParameter, valueExtractorDescriptor ) );
	}

	/**
	 * Returns the sub-types binding for the single type parameter of the super-type. E.g. for {@code IntegerProperty}
	 * and {@code Property<T>}, {@code Integer} would be returned.
	 */
	static Class<?> getSingleTypeParameterBind(TypeResolutionHelper typeResolutionHelper, Type declaredType, ValueExtractorDescriptor valueExtractorDescriptor) {
		ResolvedType resolvedType = typeResolutionHelper.getTypeResolver().resolve( declaredType );
		List<ResolvedType> resolvedTypeParameters = resolvedType.typeParametersFor( valueExtractorDescriptor.getContainerType() );

		if ( resolvedTypeParameters.isEmpty() ) {
			throw LOG.getNoValueExtractorFoundForUnwrapException( declaredType );
		}
		else {
			return resolvedTypeParameters.get( TypeVariables.getTypeParameterIndex( valueExtractorDescriptor.getExtractedTypeParameter() ) ).getErasedType();
		}
	}

}
