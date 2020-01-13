/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.calls

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirTypeParametersOwner
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.impl.FirThisReceiverExpressionImpl
import org.jetbrains.kotlin.fir.references.impl.FirImplicitThisReference
import org.jetbrains.kotlin.fir.renderWithType
import org.jetbrains.kotlin.fir.resolve.*
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutor
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.impl.FirCompositeScope
import org.jetbrains.kotlin.fir.scopes.impl.FirStaticScope
import org.jetbrains.kotlin.fir.scopes.scope
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.ConeClassLikeLookupTagImpl
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.impl.FirResolvedTypeRefImpl

interface ReceiverValue {
    val type: ConeKotlinType

    val receiverExpression: FirExpression

    fun scope(useSiteSession: FirSession, scopeSession: ScopeSession): FirScope? =
        type.scope(useSiteSession, scopeSession)
}

private fun receiverExpression(symbol: AbstractFirBasedSymbol<*>, type: ConeKotlinType): FirExpression =
    FirThisReceiverExpressionImpl(null, FirImplicitThisReference(symbol)).apply {
        typeRef = FirResolvedTypeRefImpl(null, type)
    }

class ClassDispatchReceiverValue(klassSymbol: FirClassSymbol<*>) : ReceiverValue {
    override val type: ConeKotlinType = ConeClassLikeTypeImpl(
        klassSymbol.toLookupTag(),
        (klassSymbol.fir as? FirTypeParametersOwner)?.typeParameters?.map { ConeStarProjection }?.toTypedArray().orEmpty(),
        isNullable = false
    )

    override val receiverExpression: FirExpression = receiverExpression(klassSymbol, type)
}

class ExpressionReceiverValue(
    val explicitReceiverExpression: FirExpression
) : ReceiverValue {
    override val type: ConeKotlinType
        get() = explicitReceiverExpression.typeRef.coneTypeSafe()
            ?: ConeKotlinErrorType("No type calculated for: ${explicitReceiverExpression.renderWithType()}") // TODO: assert here

    override val receiverExpression: FirExpression
        get() = explicitReceiverExpression

    override fun scope(useSiteSession: FirSession, scopeSession: ScopeSession): FirScope? {
        val qualifiedReceiver = explicitReceiverExpression as? FirResolvedQualifier
        val classId = qualifiedReceiver?.classId
        if (classId != null) {
            val lookupTag = ConeClassLikeLookupTagImpl(classId)
            val classSymbol = lookupTag.toSymbol(useSiteSession) as? FirRegularClassSymbol
            val klass = classSymbol?.fir
            if (klass != null && klass.classKind != ClassKind.OBJECT) {
                val delegateType = classSymbol.constructType(emptyArray(), isNullable = false)
                val delegateTypeScope = delegateType.scope(useSiteSession, scopeSession)
                if (delegateTypeScope != null) {
                    val staticScope = FirStaticScope(delegateTypeScope)
                    if (klass.companionObject == null) {
                        return staticScope
                    }
                    val companionScope = super.scope(useSiteSession, scopeSession)
                    if (companionScope == null) {
                        return companionScope
                    }
                    return FirCompositeScope(staticScope, companionScope)
                }
            }
        }
        return super.scope(useSiteSession, scopeSession)
    }
}

abstract class ImplicitReceiverValue<S : AbstractFirBasedSymbol<*>>(
    val boundSymbol: S,
    type: ConeKotlinType,
    private val useSiteSession: FirSession,
    private val scopeSession: ScopeSession
) : ReceiverValue {
    final override var type: ConeKotlinType = type
        private set

    var implicitScope: FirScope? = type.scope(useSiteSession, scopeSession)
        private set

    override fun scope(useSiteSession: FirSession, scopeSession: ScopeSession): FirScope? = implicitScope

    override val receiverExpression: FirExpression = receiverExpression(boundSymbol, type)

    /*
     * Should be called only in ImplicitReceiverStack
     */
    internal fun replaceType(type: ConeKotlinType) {
        if (type == this.type) return
        this.type = type
        implicitScope = type.scope(useSiteSession, scopeSession)
    }
}

class ImplicitDispatchReceiverValue(
    boundSymbol: FirClassSymbol<*>,
    type: ConeKotlinType,
    useSiteSession: FirSession,
    scopeSession: ScopeSession
) : ImplicitReceiverValue<FirClassSymbol<*>>(boundSymbol, type, useSiteSession, scopeSession) {
    val implicitCompanionScopes: List<FirScope> = run {
        val klass = boundSymbol.fir as? FirRegularClass ?: return@run emptyList()
        listOfNotNull(klass.companionObject?.scope(ConeSubstitutor.Empty, useSiteSession, scopeSession)) +
                lookupSuperTypes(klass, lookupInterfaces = false, deep = true, useSiteSession = useSiteSession).mapNotNull {
                    val superClass = (it as? ConeClassLikeType)?.lookupTag?.toSymbol(useSiteSession)?.fir as? FirRegularClass
                    superClass?.companionObject?.scope(ConeSubstitutor.Empty, useSiteSession, scopeSession)
                }
    }
}

class ImplicitExtensionReceiverValue(
    boundSymbol: FirCallableSymbol<*>,
    type: ConeKotlinType,
    useSiteSession: FirSession,
    scopeSession: ScopeSession
) : ImplicitReceiverValue<FirCallableSymbol<*>>(boundSymbol, type, useSiteSession, scopeSession)
